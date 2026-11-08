/* Modifications Copyright (C) 2026 Ettacent */

#include "twidget/sandbox_engine.h"

#include "twidget/bundle_stamp.h"
#include "twidget/json.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>

#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__OpenBSD__) || defined(__ANDROID__)
#include <stdlib.h>
#elif defined(__linux__)
#include <sys/random.h>
#elif defined(_WIN32)
#include <windows.h>
#include <bcrypt.h>
#endif

namespace twidget {

static constexpr double kInf = std::numeric_limits<double>::infinity();
static constexpr const char* kActivationPrefix = "activation:";
static constexpr const char* kHideHostGlobals =
    "(function(g){for(const n of ['__host','__hostBinary','__hostCanvas','__hostOps','__twDispatch'])if(!delete g[n])throw new Error(n);})(globalThis);";
static constexpr double kWallBackstopFactor = 10.0;
static constexpr double kWallBackstopMinMs = 5000.0;

double SandboxEngine::nowSeconds() { return QuickJSContext::now(); }
double SandboxEngine::nowMs() { return QuickJSContext::now() * 1000; }

static void randomBytes(uint8_t* buf, size_t n) {
#if defined(__APPLE__) || defined(__FreeBSD__) || defined(__OpenBSD__) || defined(__ANDROID__)
    arc4random_buf(buf, n);
#elif defined(__linux__)
    size_t got = 0;
    while (got < n) {
        ssize_t r = getrandom(buf + got, n - got, 0);
        if (r <= 0) break;
        got += size_t(r);
    }
#elif defined(_WIN32)
    BCryptGenRandom(nullptr, buf, (ULONG)n, BCRYPT_USE_SYSTEM_PREFERRED_RNG);
#else
#error "no CSPRNG for this platform"
#endif
}

static std::pair<std::string, std::string> splitPrefix(const std::string& s) {
    size_t i = s.find(':');
    if (i == std::string::npos) return {s, ""};
    return {s.substr(0, i), s.substr(i + 1)};
}

static int countOccurrences(const std::string& text, const char* needle) {
    int count = 0;
    size_t len = std::strlen(needle);
    for (size_t pos = text.find(needle); pos != std::string::npos; pos = text.find(needle, pos + len)) count++;
    return count;
}

static int countCanvasCommands(const std::string& json) {
    static constexpr const char* kKey = "\"cmds\":[";
    const size_t keyLength = std::strlen(kKey);
    int count = 0;
    for (size_t pos = json.find(kKey); pos != std::string::npos; pos = json.find(kKey, pos)) {
        size_t i = pos + keyLength;
        int depth = 1;
        bool any = false;
        for (; i < json.size() && depth > 0; i++) {
            char c = json[i];
            if (c == '"') {
                for (i++; i < json.size() && json[i] != '"'; i++) if (json[i] == '\\') i++;
                any = true;
            } else if (c == '[' || c == '{') {
                any = true;
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',') {
                if (depth == 1) count++;
            } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                any = true;
            }
        }
        if (any) count++;
        pos = i;
    }
    return count;
}

static std::string truncate(const std::string& s, size_t n) { return s.size() <= n ? s : s.substr(0, n); }

SandboxEngine::SandboxEngine(std::string html, SandboxConfig config, std::shared_ptr<SandboxResources> resources, std::shared_ptr<IntlProvider> intl)
    : thread_("widget.sandbox"), html_(std::move(html)), config_(config), resources_(std::move(resources)),
      intl_(intl ? std::move(intl) : std::make_shared<BasicIntl>()), visible_(config.visible),
      budget_(config.cpuLimitMs, config.cpuQuotaMs, config.cpuQuotaWindowMs, config.cpuKillAfterMs),
      mainBudget_(config.cpuLimitMs, config.mainThreadQuotaMs, config.cpuQuotaWindowMs, 0) {
    liveness_->engine = this;
    if (config_.context.preview) config_.context.isCopy = config_.context.canQuery = config_.context.backgroundQuery = false;
    config_.context.wallet = config_.context.wallet && config_.context.canQuery;
}

SandboxEngine::~SandboxEngine() {
    {
        std::lock_guard<std::mutex> l(liveness_->mutex);
        liveness_->engine = nullptr;
    }
    thread_.join();
    js_.reset();
}


void SandboxEngine::start() {
    thread_.async([this] { boot(); });
}

void SandboxEngine::terminate() {
    thread_.async([this] { kill("terminated"); });
}

void SandboxEngine::stop(const std::string& reason) {
    auto body = [this, reason] {
        if (dead_) return;
        kill(reason);
        if (js_) js_->cpuDeadline = -kInf;
    };
    if (thread_.isCurrent()) body();
    else thread_.async(body);
}

void SandboxEngine::boot() {
    if (started_) return;
    started_ = true;
    js_ = std::make_unique<QuickJSContext>(config_.memoryLimitBytes, JSThread::kJSStackLimit);
    js_->registerFunction("__host", [this](const std::vector<std::string>& args) {
        return hostCall(args.empty() ? "" : args[0], args.size() > 1 ? args[1] : "");
    });
    js_->registerFunction("__hostBinary", [this](const std::vector<std::string>&) {
        if (!lastBinary_) return HostReturn::null();
        auto data = std::move(*lastBinary_);
        lastBinary_.reset();
        return HostReturn::arrayBuffer(std::move(data));
    });
    task("boot", config_.cpuLimitMs, [this] {
        double t0 = nowMs();
        js_->deadline = nowSeconds() + std::max(config_.cpuLimitMs, 5000.0) / 1000;
        js_->cpuDeadline = kInf;
        std::string how = evalBundle(resources_->sandboxBundle, resources_->sandboxBytecode, "sandbox-bundle.js");
        excludeMs_ = nowMs() - t0;
        if (how == "failed") {
            if (!dead_) kill("sandbox bundle failed to load");
            return;
        }
        bool hidden = js_->captureGlobalFunction("__twDispatch");
        if (hidden) {
            try { js_->evaluate(kHideHostGlobals, "hide-host.js", false); } catch (const JSError&) { hidden = false; }
        }
        if (!hidden) {
            if (!dead_) kill("sandbox bundle failed to load");
            return;
        }
        if (delegate_) delegate_->onDiag(*this, "boot", "sandbox bundle eval " + std::to_string(int(excludeMs_)) + " ms (" + how + ")");
        resetDeadline();
        Json config = Json::object();
        config.set("memoryLimitBytes", double(config_.memoryLimitBytes));
        config.set("cpuLimitMs", config_.cpuLimitMs);
        config.set("cpuQuotaMs", config_.cpuQuotaMs);
        config.set("cpuQuotaWindowMs", config_.cpuQuotaWindowMs);
        config.set("cpuKillAfterMs", config_.cpuKillAfterMs);
        config.set("mainThreadQuotaMs", config_.mainThreadQuotaMs);
        config.set("maxTimers", config_.maxTimers);
        config.set("policy", isStrictPolicy(config_.policy) ? "strict" : "compat");
        config.set("visible", visible_);
        Json viewport = Json::object();
        viewport.set("innerWidth", config_.innerWidth);
        viewport.set("innerHeight", config_.innerHeight);
        viewport.set("dpr", config_.devicePixelRatio);
        config.set("viewport", viewport);
        config.set("theme", config_.theme);
        config.set("themeParams", sdk::themeParamsJson(sdk::checkThemeParams(config_.themeParams)));
        config.set("locale", sdk::checkLocale(config_.locale).value_or(std::string(sdk::DEFAULT_LOCALE)));
        config.set("contentSafeArea", config_.contentSafeArea.json());
        config.set("profile", sdk::PROFILE_VERSION);
        bool preloadAccepted = true;
        config.set("storage", config_.context.preview ? Json::object() : sdk::storagePreloadJson(config_.storage, &preloadAccepted));
        if (!preloadAccepted && delegate_)
            delegate_->onDiag(*this, "storage-rejected", "the preload is not { local, session } objects of strings within " + std::to_string(sdk::STORAGE_QUOTA_BYTES) +
                                                             " bytes each; the widget starts with empty storage");
        const WidgetContext& context = config_.context;
        const bool revealsViewer = !context.preview && context.canQuery;
        putData(config, sdk::checkWidgetData(config_.data, context.isCopy));
        config.set("isCopy", context.isCopy);
        config.set("canQuery", context.canQuery);
        config.set("isPreview", context.preview);
        std::optional<std::string> chatType = context.preview || context.isCopy ? std::nullopt : sdk::checkChatType(context.chatType);
        config.set("chatType", chatType ? Json(*chatType) : Json());
        sdk::CheckedViewer viewer = revealsViewer ? sdk::checkViewer(context.viewer) : sdk::CheckedViewer{};
        config.set("viewer", viewer.viewer ? sdk::viewerJson(*viewer.viewer) : Json());
        config.set("wallet", context.wallet);
        for (const std::string& field : viewer.rejected)
            if (delegate_) delegate_->onDiag(*this, "data-rejected", "viewer." + field + ": " + std::string(sdk::viewerFieldRule(field)) + "; the widget does not see it");
        Json features = Json::array();
        for (const std::string& feature : config_.features)
            if (feature == "webAnimations" || feature == "noInPlaceText" || feature == "noAttrFast" || feature == "noCanvasRect") features.push(Json(feature));
        config.set("features", features);
        Json boot = Json::object();
        boot.set("html", html_);
        boot.set("config", config);
        callTw("boot", boot.dump());
    });
    task("boot", config_.cpuLimitMs, [this] { callTw("loaded", ""); });
    if (!dead_ && delegate_) delegate_->onLoaded(*this);
    postStats(true);
    auto queued = std::move(pendingBeforeBoot_);
    pendingBeforeBoot_.clear();
    for (auto& [kind, arg] : queued) if (!dead_) dispatch(kind, arg);
}


void SandboxEngine::event(const std::string& json) {
    if (!config_.context.preview) enqueue("event", json);
}
void SandboxEngine::activationEvent(const std::string& json, int gesture) {
    if (!config_.context.preview) enqueue(kActivationPrefix + std::to_string(gesture), json);
}
static double coarseNowMs(double ms) { return std::floor(ms / 0.1) * 0.1; }

void SandboxEngine::frame(double time) { enqueue("frame", Json::number(coarseNowMs(time))); }
void SandboxEngine::viewport(double innerWidth, double innerHeight, double dpr) {
    Json v = Json::object();
    v.set("innerWidth", innerWidth);
    v.set("innerHeight", innerHeight);
    v.set("dpr", dpr);
    enqueue("viewport", v.dump());
}
void SandboxEngine::resize(const std::string& entriesJson) { enqueue("resize", entriesJson); }
void SandboxEngine::theme(const std::string& colorScheme, const std::map<std::string, std::string>& themeParams) {
    Json t = Json::object();
    t.set("theme", colorScheme);
    t.set("themeParams", sdk::themeParamsJson(sdk::checkThemeParams(themeParams)));
    enqueue("theme", t.dump());
}

Json WidgetContentSafeArea::json() const {
    Json out = Json::object();
    auto put = [&](const char* name, double v) { out.set(name, std::isfinite(v) ? v : 0.0); };
    put("top", top);
    put("right", right);
    put("bottom", bottom);
    put("left", left);
    put("cornerLeft", cornerLeft);
    put("cornerRight", cornerRight);
    return out;
}

void SandboxEngine::contentSafeArea(const WidgetContentSafeArea& area) {
    thread_.async([this, area] {
        if (dead_) return;
        if (!js_) { config_.contentSafeArea = area; return; }
        input("safeArea", area.json().dump());
    });
}

void SandboxEngine::setData(const WidgetData& data) {
    sdk::CheckedWidgetData checked = sdk::checkWidgetData(data, config_.context.isCopy);
    thread_.async([this, checked] {
        if (dead_) return;
        Json event = Json::object();
        event.set("type", "data");
        putData(event, checked);
        input("bridge", event.dump());
    });
}

static std::string joinFields(const std::vector<std::string>& fields) {
    std::string out;
    for (const std::string& f : fields) out += (out.empty() ? "" : " and ") + f;
    return out;
}

void SandboxEngine::putData(Json& event, const sdk::CheckedWidgetData& checked) {
    event.set("chatData", checked.chatData ? Json(*checked.chatData) : Json());
    event.set("publicData", checked.publicData ? Json(*checked.publicData) : Json());
    if (checked.rejected.empty() || !delegate_) return;
    delegate_->onDiag(*this, "data-rejected", joinFields(checked.rejected) + ": not text of at most " + std::to_string(sdk::MAX_WIDGET_DATA_BYTES) + " bytes (UTF-8, no NUL); the widget sees null");
}

void SandboxEngine::pressBegan() {
    double at = nowMs();
    thread_.async([this, at] { haptics_.press(at); });
}
void SandboxEngine::pressEnded() {
    double at = nowMs();
    thread_.async([this, at] { haptics_.release(at); });
}

void SandboxEngine::setVisible(bool v) {
    thread_.async([this, v] {
        if (dead_ || visible_ == v) return;
        visible_ = v;
        if (v && requests_.show(nowMs()) && heldRequest_) {
            HeldRequest held = std::move(*heldRequest_);
            heldRequest_.reset();
            deliverRequest(held.id, held.data);
        }
        if (!js_) return;
        dispatch("visibility", std::string("{\"visible\":") + (v ? "true" : "false") + "}");
        if (v) {
            if (frameWanted_) { frameWanted_ = false; if (delegate_) delegate_->onRequestFrame(*this); }
            if (!deferred_.empty()) wake();
        }
    });
}

std::string SandboxEngine::probe(const std::string& code) {
    return thread_.sync([&]() -> std::string {
        if (!js_ || dead_) return "ERROR no runtime";
        try {
            auto r = js_->evaluate("(function(){ try { return String(" + code + "); } catch (e) { return 'THREW ' + e; } })()", "probe.js");
            return r.string.value_or(r.number ? Json::number(*r.number) : std::string("undefined"));
        } catch (const JSError& e) {
            return std::string("ERROR ") + e.what();
        }
    });
}

void SandboxEngine::enqueue(const std::string& kind, const std::string& arg) {
    thread_.async([this, kind, arg] { input(kind, arg); });
}

void SandboxEngine::input(const std::string& kind, const std::string& arg) {
    if (dead_) return;
    if (!js_) { pendingBeforeBoot_.emplace_back(kind, arg); return; }
    if (kind == "frame" && !visible_) { frameWanted_ = true; return; }
    dispatch(kind, arg);
}

bool SandboxEngine::dispatch(const std::string& kind, const std::string& arg) {
    if (dead_) return true;
    if (kind.rfind(kActivationPrefix, 0) == 0) {
        int gesture = std::atoi(kind.c_str() + std::strlen(kActivationPrefix));
        activeGesture_ = visible_ && gesture > consumedGesture_ ? gesture : 0;
        bool ran = dispatch("event", arg);
        activeGesture_ = 0;
        return ran;
    }
    double now = nowMs();
    CpuDecision js = budget_.decide(kind, now);
    CpuDecision main = mainBudget_.decide(kind, now);
    if (!js.run || !main.run) {
        if (budget_.shouldKill(now)) {
            char buf[256];
            snprintf(buf, sizeof buf, "CPU quota: throttled for %.1f s (%d ms per %d ms, cpuKillAfterMs=%d)", budget_.throttledFor(now) / 1000,
                     int(config_.cpuQuotaMs), int(config_.cpuQuotaWindowMs), int(config_.cpuKillAfterMs));
            kill(buf);
            return true;
        }
        deferTask(kind, arg);
        stats_.throttledTasks++;
        throttledSinceDiag_[kind]++;
        if (now - lastThrottleDiag_ >= 1000) {
            lastThrottleDiag_ = now;
            std::string parts;
            for (auto& [k, n] : throttledSinceDiag_) {
                if (!parts.empty()) parts += ", ";
                parts += std::to_string(n) + " " + k + (n == 1 ? "" : "s");
            }
            throttledSinceDiag_.clear();
            std::string window = js.run
                ? std::to_string(int(mainBudget_.used(now))) + " ms of the " + std::to_string(int(config_.mainThreadQuotaMs)) + " ms per " +
                      std::to_string(int(config_.cpuQuotaWindowMs)) + " ms main-thread budget used by layout queries and ops"
                : std::to_string(int(budget_.used(now))) + " ms of the " + std::to_string(int(config_.cpuQuotaMs)) + " ms per " +
                      std::to_string(int(config_.cpuQuotaWindowMs)) + " ms budget used";
            if (delegate_) delegate_->onDiag(*this, "cpu-throttled", "deferred " + parts + ": " + window);
        }
        scheduleWake(std::max(js.run ? 0 : js.retryAt, main.run ? 0 : main.retryAt) / 1000);
        return false;
    }
    task(kind, js.sliceMs, [&] { callTw(kind, arg); });
    return true;
}

void SandboxEngine::deferTask(const std::string& kind, const std::string& arg) {
    if (kind == "frame") {
        for (auto& item : deferred_) if (item.kind == "frame") { item.arg = arg; return; }
    }
    if (kind == "timer") {
        for (auto& item : deferred_) if (item.kind == "timer" && item.arg == arg) return;
    }
    deferred_.push_back({kind, arg});
}

void SandboxEngine::scheduleWake(double at) {
    if (wakeTimer_ && at >= wakeAt_) return;
    if (wakeTimer_) wakeTimer_->cancel();
    wakeAt_ = at;
    wakeTimer_ = thread_.after(std::max(0.0, at - nowSeconds()), [this] { wake(); });
}

void SandboxEngine::wake() {
    wakeTimer_.reset();
    wakeAt_ = kInf;
    hiddenTickAt_ = 0;
    auto items = std::move(deferred_);
    deferred_.clear();
    for (size_t i = 0; i < items.size(); i++) {
        if (dead_) return;
        if (!dispatch(items[i].kind, items[i].arg)) {
            deferred_.insert(deferred_.begin(), items.begin() + long(i), items.end());
            return;
        }
    }
}

void SandboxEngine::onTimerFired(int id) {
    timers_.erase(id);
    if (!visible_) {
        deferTask("timer", std::to_string(id));
        if (hiddenTickAt_ == 0) hiddenTickAt_ = nowSeconds() + 1;
        scheduleWake(hiddenTickAt_);
        return;
    }
    dispatch("timer", std::to_string(id));
}

static Json answerEvent(int64_t id) {
    Json event = Json::object();
    event.set("type", "answer");
    event.set("id", id);
    return event;
}


HostReturn SandboxEngine::hostCall(const std::string& kind, const std::string& arg) {
    if (dead_) return HostReturn::undefined();
    if (kind == "ops") {
        int ops = countOccurrences(arg, "\"op\":");
        stats_.ops += ops;
        taskOpsCostMs_ += CpuBudget::opsCostMs(ops, countCanvasCommands(arg));
        if (delegate_) delegate_->onOps(*this, arg);
        return HostReturn::undefined();
    }
    if (kind == "query") return HostReturn::string(syncQuery(arg));
    if (kind == "now") return HostReturn::number(coarseNowMs(nowMs()));
    if (kind == "timer") {
        auto pair = Json::parse(arg);
        if (!pair || !pair->isArray() || pair->size() != 2 || !(*pair)[0].isNumber()) return HostReturn::undefined();
        int id = (*pair)[0].asInt();
        double delay = std::min(std::max(0.0, (*pair)[1].asNumber()) / 1000, kMaxTimerDelaySeconds);
        if (!visible_) delay = std::max(delay, 1.0 + delay / 10);
        auto replaced = timers_.find(id);
        if (replaced != timers_.end()) {
            replaced->second->cancel();
            timers_.erase(replaced);
        } else if (int(timers_.size()) >= config_.maxTimers) {
            if (!timerLimitReported_) {
                timerLimitReported_ = true;
                if (delegate_) delegate_->onDiag(*this, "timer-limit-enforced", "engine refused a timer beyond maxTimers=" + std::to_string(config_.maxTimers) + "; the shim did not stop it");
            }
            return HostReturn::undefined();
        }
        timers_[id] = thread_.after(delay, [this, id] {
            if (dead_) return;
            onTimerFired(id);
        });
        return HostReturn::undefined();
    }
    if (kind == "clearTimer") {
        char* end = nullptr;
        long id = std::strtol(arg.c_str(), &end, 10);
        if (end && *end == '\0') {
            auto it = timers_.find(int(id));
            if (it != timers_.end()) { it->second->cancel(); timers_.erase(it); }
        }
        return HostReturn::undefined();
    }
    if (kind == "raf") {
        if (!visible_) { frameWanted_ = true; return HostReturn::undefined(); }
        if (delegate_) delegate_->onRequestFrame(*this);
        return HostReturn::undefined();
    }
    if (kind == "log") {
        auto [level, text] = splitPrefix(arg);
        if (delegate_) delegate_->onLog(*this, level, truncate(text, 4000));
        return HostReturn::undefined();
    }
    if (kind == "diag") {
        auto [k, detail] = splitPrefix(arg);
        if (delegate_) delegate_->onDiag(*this, truncate(k, 64), truncate(detail, 4000));
        return HostReturn::undefined();
    }
    if (kind == "listen") { if (delegate_) delegate_->onListen(*this, arg); return HostReturn::undefined(); }
    if (kind == "pd") { if (delegate_) delegate_->onPreventDefault(*this, arg); return HostReturn::undefined(); }
    if (kind == "anim") { if (delegate_) delegate_->onAnim(*this, arg); return HostReturn::undefined(); }
    if (kind == "bridge") {
        auto call = Json::parse(arg, Json::Surrogates::Keep);
        const Json* method = call && call->isArray() && call->size() > 0 ? &call->arr()[0] : nullptr;
        std::optional<sdk::BridgeCall> checked = call && call->isArray() ? sdk::checkBridgeCall((*call)[0], (*call)[1]) : std::nullopt;
        std::string name = !method ? "undefined" : method->isString() ? truncate(method->asString(), 64) : truncate(method->dump(), 64);
        if (!checked) {
            if (rejectedBridgeReported_ < sdk::MAX_REJECTION_REPORTS && delegate_) {
                rejectedBridgeReported_++;
                delegate_->onDiag(*this, "bridge-rejected", name + ": unknown method or malformed arguments");
            }
            return HostReturn::undefined();
        }
        if (config_.context.preview && !sdk::allowedInPreview(name)) {
            if (previewBlockedReported_ < sdk::MAX_REJECTION_REPORTS && delegate_) {
                previewBlockedReported_++;
                delegate_->onDiag(*this, "preview-blocked", name + " ignored: a preview (an inline result not sent yet) takes no actions");
            }
            if (checked->request || checked->wallet) {
                Json event = answerEvent(checked->request ? checked->request->id : checked->wallet->id);
                event.set("error", "NOT_ALLOWED");
                event.set("reason", "preview");
                enqueue("bridge", event.dump());
            }
            return HostReturn::undefined();
        }
        if (checked->resizeAnchor) {
            if (delegate_) delegate_->onResizeAnchor(*this, *checked->resizeAnchor);
            return HostReturn::undefined();
        }
        if (checked->watchPublicData) {
            if (delegate_) delegate_->onPublicDataWatch(*this, *checked->watchPublicData);
            return HostReturn::undefined();
        }
        if (checked->request) {
            request(checked->request->id, checked->request->data, checked->request->wallet);
            return HostReturn::undefined();
        }
        if (checked->wallet) {
            walletCall(checked->wallet->id, checked->wallet->call);
            return HostReturn::undefined();
        }
        if (isStrictPolicy(config_.policy) && sdk::isPrivilegedMethod(name)) {
            if (!(activeGesture_ > consumedGesture_ && visible_)) {
                if (blockedBridgeReported_ < sdk::MAX_REJECTION_REPORTS && delegate_) {
                    blockedBridgeReported_++;
                    delegate_->onDiag(*this, "activation-blocked", name + " ignored: it needs a tap on the visible widget and runs in that tap's handler, one call per tap (called from the " + currentKind_ + " task)");
                }
                return HostReturn::undefined();
            }
            consumedGesture_ = activeGesture_;
            activeGesture_ = 0;
        }
        if (checked->copyText) {
            if (delegate_) delegate_->onBridgeCopyText(*this, *checked->copyText);
            return HostReturn::undefined();
        }
        const WidgetAction& action = *checked->action;
        if (action.kind == WidgetAction::Haptic) {
            sdk::HapticGate::Decision d = haptics_.decide(nowMs(), visible_);
            if (d == sdk::HapticGate::Decision::Report && delegate_)
                delegate_->onDiag(*this, "haptic-limit", visible_ ? "haptics over 5 per burst or 10 per second, or outside a touch after a 1 s series, are dropped" : "haptics of a hidden widget are dropped");
            if (d != sdk::HapticGate::Decision::Play) return HostReturn::undefined();
        }
        if (delegate_) {
            delegate_->onBridgeAction(*this, action);
            delegate_->onBridge(*this, name, (*call)[1].dump());
        }
        return HostReturn::undefined();
    }
    if (kind == "storage") {
        if (config_.context.preview) return HostReturn::undefined();
        if (arg.size() > sdk::MAX_STORAGE_COMMIT_BYTES) {
            rejectStorage("a commit of " + std::to_string(arg.size()) + " bytes cannot be within the quota");
            return HostReturn::undefined();
        }
        auto parsed = Json::parse(arg, Json::Surrogates::Keep);
        std::optional<sdk::StorageCommit> commit = parsed ? sdk::checkStorageCommit(*parsed) : std::nullopt;
        if (!commit || (!commit->local && !commit->session)) {
            rejectStorage("a storage commit that is not { local?, session? } objects of strings within " + std::to_string(sdk::STORAGE_QUOTA_BYTES) +
                          " bytes each; the client keeps the previous content");
            return HostReturn::undefined();
        }
        if (delegate_) {
            if (commit->local) delegate_->onStorage(*this, "local", *commit->local);
            if (commit->session) delegate_->onStorage(*this, "session", *commit->session);
        }
        return HostReturn::undefined();
    }
    if (kind == "intl") {
        auto req = Json::parse(arg);
        if (!req || !req->isObject()) return HostReturn::string("");
        return HostReturn::string(intl_->format(*req));
    }
    if (kind == "random") {
        long n = std::strtol(arg.c_str(), nullptr, 10);
        n = std::min(std::max(n, 0L), 65536L);
        std::vector<uint8_t> bytes(static_cast<size_t>(n), 0);
        if (n > 0) randomBytes(bytes.data(), bytes.size());
        std::string out = "[";
        for (size_t i = 0; i < bytes.size(); i++) { if (i) out.push_back(','); out += std::to_string(bytes[i]); }
        out.push_back(']');
        return HostReturn::string(out);
    }
    if (kind == "stat") {
        if (auto s = Json::parse(arg); s && s->isObject()) {
            stats_.queryCacheHits = (*s)["cacheHits"].asInt();
            stats_.missedMutations = (*s)["missed"].asInt();
        }
        return HostReturn::undefined();
    }
    return HostReturn::undefined();
}


namespace {

const char* refusalText(sdk::RequestVerdict verdict) {
    switch (verdict) {
    case sdk::RequestVerdict::Disabled: return "the server allows no requests for this message (no can_query: a widget sent without allow_queries, or a forwarded copy of one sent without fwd_allow_queries)";
    case sdk::RequestVerdict::Busy: return "a request is already in flight";
    case sdk::RequestVerdict::NoActivation: return "it needs a tap on the visible widget, one request per tap; this message allows no background requests (no background_query)";
    case sdk::RequestVerdict::RateLimited: return "background requests at most once per 5 s";
    case sdk::RequestVerdict::NoWallet: return "the client has no wallet for this user (no WidgetContext::wallet)";
    default: return "";
    }
}

const char* walletRefusalText(sdk::RequestVerdict verdict) {
    switch (verdict) {
    case sdk::RequestVerdict::NoActivation: return "it needs a tap on the visible widget, or the answer to a call made by a tap within 10 s; one call each";
    case sdk::RequestVerdict::Busy: return "a request or a wallet call is already in flight";
    default: return refusalText(verdict);
    }
}

}

std::string SandboxEngine::refusal(int64_t id, std::string_view error, std::string_view reason, const std::string& detail) {
    if (blockedRequestsReported_ < sdk::MAX_REJECTION_REPORTS && delegate_) {
        blockedRequestsReported_++;
        delegate_->onDiag(*this, "request-blocked", detail);
    }
    Json event = answerEvent(id);
    event.set("error", error);
    event.set("reason", reason);
    return event.dump();
}

void SandboxEngine::request(int64_t id, const std::string& data, bool wallet) {
    bool strict = isStrictPolicy(config_.policy);
    bool pressed = !strict || (activeGesture_ > consumedGesture_ && visible_);
    const WidgetContext& c = config_.context;
    sdk::RequestVerdict verdict = requests_.decide(nowMs(), {pressed, visible_, c.canQuery, c.backgroundQuery, wallet, c.wallet});
    if (std::optional<std::string_view> error = sdk::requestRefusal(verdict)) {
        std::string name(sdk::requestVerdictName(verdict));
        std::string why = wallet && verdict == sdk::RequestVerdict::NoActivation ? "a request for the wallet needs a tap on the visible widget, one request per tap" : refusalText(verdict);
        return enqueue("bridge", refusal(id, *error, name, "request refused (" + name + "): " + why));
    }
    if (verdict == sdk::RequestVerdict::Foreground && strict) {
        consumedGesture_ = activeGesture_;
        activeGesture_ = 0;
    }
    if (verdict == sdk::RequestVerdict::Held) heldRequest_ = HeldRequest{id, data};
    else deliverRequest(id, data, wallet);
}

void SandboxEngine::walletCall(int64_t id, const WalletCall& call) {
    bool strict = isStrictPolicy(config_.policy);
    bool pressed = !strict || (activeGesture_ > consumedGesture_ && visible_);
    sdk::RequestVerdict verdict = requests_.decideWallet(nowMs(), {pressed, visible_, config_.context.canQuery, config_.context.wallet});
    if (std::optional<std::string_view> error = sdk::requestRefusal(verdict)) {
        std::string name(sdk::requestVerdictName(verdict));
        return enqueue("bridge", refusal(id, *error, name, "wallet." + std::string(call.methodName()) + " refused (" + name + "): " + walletRefusalText(verdict)));
    }
    if (verdict == sdk::RequestVerdict::Foreground && strict) {
        consumedGesture_ = activeGesture_;
        activeGesture_ = 0;
    }
    WidgetRequestResponder respond = responder(id, true);
    if (delegate_) delegate_->onBridgeWallet(*this, call, std::move(respond));
    else respond.unsupported();
}

WidgetRequestResponder SandboxEngine::responder(int64_t id, bool wallet) {
    auto once = std::make_shared<std::atomic<bool>>(false);
    return WidgetRequestResponder([life = liveness_, once, id, wallet](const std::optional<WidgetRequestOutcome>& outcome) {
        if (once->exchange(true)) return;
        std::lock_guard<std::mutex> l(life->mutex);
        if (SandboxEngine* engine = life->engine) engine->thread_.async([engine, id, outcome, wallet] { engine->answerRequest(id, outcome, wallet); });
    });
}

void SandboxEngine::deliverRequest(int64_t id, const std::string& data, bool wallet) {
    WidgetRequestResponder respond = responder(id, false);
    if (delegate_) delegate_->onBridgeRequest(*this, WidgetRequest{data, wallet}, std::move(respond));
    else respond.unsupported();
}

void SandboxEngine::answerRequest(int64_t id, const std::optional<WidgetRequestOutcome>& outcome, bool wallet) {
    if (dead_) return;
    if (!outcome) {
        requests_.finish();
        return input("bridge", refusal(id, "NOT_ALLOWED", "unsupported", wallet ? "wallet call refused: the client handles no wallet calls (onWallet)" : "request refused: the client handles no requests (onRequest)"));
    }
    Json event = answerEvent(id);
    if (outcome->failure != WidgetRequestFailure::None) {
        requests_.finish();
        event.set("error", wallet ? sdk::walletFailureCode(outcome->failure) : sdk::requestFailureCode(outcome->failure));
    } else if (std::optional<sdk::CheckedAnswer> checked = sdk::checkRequestAnswer(outcome->answer)) {
        requests_.finish(nowMs());
        event.set("result", checked->result ? Json(*checked->result) : Json());
    } else {
        requests_.finish();
        if (delegate_)
            delegate_->onDiag(*this, "answer-rejected", "the answer's result is not text of at most " + std::to_string(sdk::MAX_REQUEST_RESULT_BYTES) +
                                                            " bytes (UTF-8, no NUL); the widget gets INVALID_ANSWER");
        event.set("error", "INVALID_ANSWER");
    }
    input("bridge", event.dump());
}

void SandboxEngine::rejectStorage(const std::string& detail) {
    if (rejectedStorageReported_ >= sdk::MAX_REJECTION_REPORTS || !delegate_) return;
    rejectedStorageReported_++;
    delegate_->onDiag(*this, "storage-rejected", detail);
}

std::string SandboxEngine::syncQuery(const std::string& json) {
    if (!delegate_) return "{\"error\":\"no host\"}";
    if (!queryLimitHit_.empty()) return "{\"error\":\"query budget exceeded\"}";
    double t0 = nowSeconds();
    QueryAnswer answer = delegate_->onQuery(*this, json);
    double dt = nowSeconds() - t0;
    js_->deadline += dt;
    stats_.queries++;
    stats_.queryMs += dt * 1000;
    taskQueryMs_ += dt * 1000;
    taskQueries_++;
    queryLimitHit_ = CpuBudget::queryLimitHit(taskQueries_, taskQueryMs_);
    if (!queryLimitHit_.empty()) {
        js_->cpuDeadline = -std::numeric_limits<double>::infinity();
    }
    lastBinary_ = std::move(answer.binary);
    return answer.json;
}


void SandboxEngine::kill(const std::string& reason) {
    if (dead_) return;
    dead_ = true;
    heldRequest_.reset();
    pendingBeforeBoot_.clear();
    for (auto& [_, t] : timers_) t->cancel();
    timers_.clear();
    deferred_.clear();
    if (wakeTimer_) { wakeTimer_->cancel(); wakeTimer_.reset(); }
    if (trailingStats_) { trailingStats_->cancel(); trailingStats_.reset(); }
    if (delegate_) delegate_->onDied(*this, reason);
}

void SandboxEngine::handleError(const std::string& text, const std::string& where) {
    if (js_ && js_->interrupted && !queryLimitHit_.empty()) {
        return kill(CpuBudget::queryLimitReason(queryLimitHit_, currentKind_) + " (" + where + ")");
    }
    if (js_ && js_->interrupted) {
        return kill("CPU limit exceeded: more than " + std::to_string(int(currentSliceMs_)) + " ms of continuous execution (" + where + ")");
    }
    std::string lower = text;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    if (lower.find("out of memory") != std::string::npos) return kill("Memory limit exceeded (" + std::to_string(config_.memoryLimitBytes >> 20) + " MB)");
    if (delegate_) delegate_->onLog(*this, "error", truncate("Uncaught " + text, 4000));
}

void SandboxEngine::drainJobs() {
    js_->executePendingJobs([this](const JSError& e) { handleError(e.what(), "promise job"); });
}

std::optional<std::string> SandboxEngine::callTw(const std::string& kind, const std::string& arg) {
    if (dead_) return std::nullopt;
    try {
        return js_->call("__twDispatch", {kind, arg}).string;
    } catch (const JSError& e) {
        handleError(e.what(), kind);
        return std::nullopt;
    }
}

void SandboxEngine::settle() {
    for (int i = 0; i < 20 && !dead_; i++) {
        drainJobs();
        if (dead_) return;
        auto more = callTw("endTick", "");
        if (!(more && *more == "1") && !js_->hasPendingJob()) break;
    }
}

std::string SandboxEngine::evalBundle(const std::string& source, const std::vector<uint8_t>& bytecode, const std::string& filename) {
    if (auto refusal = BundleStamp::refusal(*resources_)) {
        if (delegate_) delegate_->onDiag(*this, "bundle-mismatch", *refusal);
        return "failed";
    }
    if (!bytecode.empty()) {
        try {
            js_->evaluateBytecode(bytecode, source);
            return "bytecode";
        } catch (const JSError& e) {
            std::string msg = e.what();
            if (delegate_) delegate_->onDiag(*this, "bytecode", filename + ": falling back to source — " + msg.substr(0, msg.find('\n')));
        }
    }
    try {
        js_->evaluate(source, filename, false);
    } catch (const JSError& e) {
        handleError(e.what(), filename);
        return "failed";
    }
    return "source";
}

void SandboxEngine::evalScript(const std::string& code, const std::string& filename, bool asModule) {
    try { js_->evaluate(code, filename, asModule); } catch (const JSError& e) { handleError(e.what(), filename); }
}

void SandboxEngine::pumpScripts() {
    for (int i = 0; i < 1000 && !dead_; i++) {
        auto next = callTw("nextScript", "");
        if (!next || next->empty()) break;
        auto s = Json::parse(*next);
        if (!s || !s->isObject()) break;
        int id = (*s)["id"].asInt();
        bool ok = true;
        resetDeadline();
        if (const Json* src = s->find("src"); src && src->isString()) {
            ok = false;
            if (delegate_) delegate_->onDiag(*this, "script-skipped", "external script not available: " + truncate(src->asString(), 200));
        } else if (const Json* code = s->find("code"); code && code->isString()) {
            evalScript(code->asString(), "inline-script-" + std::to_string(id) + ".js", (*s)["type"].asString() == "module");
        }
        if (dead_) return;
        settle();
        callTw("scriptDone", "[" + std::to_string(id) + "," + (ok ? "true" : "false") + "]");
        settle();
    }
}

void SandboxEngine::resetDeadline() {
    js_->interrupted = false;
    js_->cpuDeadline = QuickJSContext::threadCpuTime() + currentSliceMs_ / 1000;
    js_->deadline = nowSeconds() + std::max(currentSliceMs_ * kWallBackstopFactor, kWallBackstopMinMs) / 1000;
}

void SandboxEngine::task(const std::string& kind, double sliceMs, const std::function<void()>& body) {
    if (dead_) return;
    currentKind_ = kind;
    currentSliceMs_ = sliceMs;
    excludeMs_ = 0;
    taskQueryMs_ = 0;
    taskQueries_ = 0;
    taskOpsCostMs_ = 0;
    queryLimitHit_ = {};
    double c0 = QuickJSContext::threadCpuTime();
    resetDeadline();
    body();
    if (!dead_) settle();
    if (!dead_) pumpScripts();
    if (!dead_ && !queryLimitHit_.empty()) kill(CpuBudget::queryLimitReason(queryLimitHit_, kind));
    stats_.tasks++;
    double cpuMs = (QuickJSContext::threadCpuTime() - c0) * 1000 - excludeMs_;
    stats_.jsMs += cpuMs;
    double end = nowMs();
    budget_.record(kind, cpuMs, end);
    mainBudget_.record(kind, taskQueryMs_ + taskOpsCostMs_, end);
    stats_.cpuWindowMs = budget_.used(end);
    double now = nowSeconds();
    if (now - lastMemCheck_ > 1 && !dead_) {
        lastMemCheck_ = now;
        stats_.memoryBytes = js_->memoryUsed();
    }
    postStats(false);
}

void SandboxEngine::chargeValidator(double ms) {
    if (!(ms > 0)) return;
    auto record = [this, ms] {
        if (dead_) return;
        double now = nowMs();
        budget_.record("validator", ms, now);
        stats_.validatorMs += ms;
        stats_.cpuWindowMs = budget_.used(now);
        postStats(false);
    };
    if (thread_.isCurrent()) record();
    else thread_.async(record);
}

void SandboxEngine::postStats(bool force) {
    double now = nowSeconds();
    if (force || now - lastStatsPost_ > 0.2) {
        lastStatsPost_ = now;
        if (trailingStats_) { trailingStats_->cancel(); trailingStats_.reset(); }
        if (delegate_) delegate_->onStats(*this, stats_);
    } else if (!trailingStats_) {
        trailingStats_ = thread_.after(0.2, [this] {
            trailingStats_.reset();
            postStats(true);
        });
    }
}

}

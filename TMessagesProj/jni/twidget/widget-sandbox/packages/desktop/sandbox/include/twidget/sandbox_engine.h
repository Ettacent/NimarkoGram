/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/cpu_budget.h"
#include "twidget/intl.h"
#include "twidget/js_thread.h"
#include "twidget/quickjs_context.h"
#include "twidget/resources.h"
#include "twidget/sdk_policy.h"

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace twidget {

struct WidgetContentSafeArea {
    double top = 0, right = 0, bottom = 0, left = 0, cornerLeft = 0, cornerRight = 0;
    Json json() const;
};

struct SandboxConfig {
    size_t memoryLimitBytes = size_t(8) << 20;
    double cpuLimitMs = 500;
    double cpuQuotaMs = 300;
    double cpuQuotaWindowMs = 1000;
    double cpuKillAfterMs = 0;
    double mainThreadQuotaMs = 100;
    int maxTimers = 64;
    bool visible = true;
    double innerWidth = 400;
    double innerHeight = 300;
    double devicePixelRatio = 1;
    std::string theme = "light";
    std::map<std::string, std::string> themeParams;
    std::string locale = std::string(sdk::DEFAULT_LOCALE);
    WidgetContentSafeArea contentSafeArea;
    WidgetStorage storage;
    WidgetData data;
    WidgetContext context;
    std::string policy = "strict";
    std::vector<std::string> features;
};

inline bool isStrictPolicy(const std::string& policy) { return policy != "compat"; }

struct SandboxStats {
    double jsMs = 0;
    int tasks = 0;
    size_t memoryBytes = 0;
    int ops = 0;
    int queries = 0;
    double queryMs = 0;
    int queryCacheHits = 0;
    int missedMutations = 0;
    int throttledTasks = 0;
    double cpuWindowMs = 0;
    double validatorMs = 0;
};

struct QueryAnswer {
    std::string json;
    std::optional<std::vector<uint8_t>> binary;
};

class SandboxEngine;

class WidgetRequestResponder {
public:
    void operator()(const WidgetRequestOutcome& outcome) const { settle_(outcome); }
    void unsupported() const { settle_(std::nullopt); }

private:
    friend class SandboxEngine;
    explicit WidgetRequestResponder(std::function<void(const std::optional<WidgetRequestOutcome>&)> settle) : settle_(std::move(settle)) {}
    std::function<void(const std::optional<WidgetRequestOutcome>&)> settle_;
};

class SandboxEngineDelegate {
public:
    virtual ~SandboxEngineDelegate() = default;
    virtual void onOps(SandboxEngine& engine, const std::string& json) = 0;
    virtual QueryAnswer onQuery(SandboxEngine& engine, const std::string& json) = 0;
    virtual void onListen(SandboxEngine& engine, const std::string& json) = 0;
    virtual void onPreventDefault(SandboxEngine& engine, const std::string& json) = 0;
    virtual void onRequestFrame(SandboxEngine& engine) = 0;
    virtual void onLog(SandboxEngine& engine, const std::string& level, const std::string& text) = 0;
    virtual void onDiag(SandboxEngine& engine, const std::string& kind, const std::string& detail) = 0;
    virtual void onBridgeAction(SandboxEngine&, const WidgetAction&) {}
    virtual void onResizeAnchor(SandboxEngine&, const std::string&  ) {}
    virtual void onPublicDataWatch(SandboxEngine&, bool  ) {}
    virtual void onStorage(SandboxEngine&, const std::string&  , const StorageArea&  ) {}
    virtual void onBridgeRequest(SandboxEngine&, const WidgetRequest&, WidgetRequestResponder respond) { respond.unsupported(); }
    virtual void onBridgeWallet(SandboxEngine&, const WalletCall&, WidgetRequestResponder respond) { respond.unsupported(); }
    virtual void onBridgeCopyText(SandboxEngine&, const std::string&  ) {}
    virtual void onBridge(SandboxEngine&, const std::string&  , const std::string&  ) {}
    virtual void onLoaded(SandboxEngine& engine) = 0;
    virtual void onStats(SandboxEngine& engine, const SandboxStats& stats) = 0;
    virtual void onDied(SandboxEngine& engine, const std::string& reason) = 0;
    virtual void onAnim(SandboxEngine&, const std::string&) {}
};

class SandboxEngine {
public:
    SandboxEngine(std::string html, SandboxConfig config, std::shared_ptr<SandboxResources> resources,
                  std::shared_ptr<IntlProvider> intl = nullptr);
    ~SandboxEngine();
    SandboxEngine(const SandboxEngine&) = delete;
    SandboxEngine& operator=(const SandboxEngine&) = delete;

    void setDelegate(SandboxEngineDelegate* delegate) { delegate_ = delegate; }
    JSThread& thread() { return thread_; }

    void start();
    void terminate();
    void stop(const std::string& reason);
    void chargeValidator(double ms);

    void event(const std::string& json);
    void activationEvent(const std::string& json, int gesture);
    void frame(double time);
    void viewport(double innerWidth, double innerHeight, double dpr);
    void resize(const std::string& entriesJson);
    void theme(const std::string& colorScheme, const std::map<std::string, std::string>& themeParams = {});
    void contentSafeArea(const WidgetContentSafeArea& area);
    void setVisible(bool visible);
    void pressBegan();
    void pressEnded();
    void setData(const WidgetData& data);

    std::string probe(const std::string& code);

private:
    struct Deferred { std::string kind; std::string arg; };
    struct Liveness {
        std::mutex mutex;
        SandboxEngine* engine = nullptr;
    };

    void boot();
    void enqueue(const std::string& kind, const std::string& arg);
    void input(const std::string& kind, const std::string& arg);
    bool dispatch(const std::string& kind, const std::string& arg);
    void deferTask(const std::string& kind, const std::string& arg);
    void scheduleWake(double atSeconds);
    void wake();
    void onTimerFired(int id);
    HostReturn hostCall(const std::string& kind, const std::string& arg);
    void request(int64_t id, const std::string& data, bool wallet = false);
    void walletCall(int64_t id, const WalletCall& call);
    void deliverRequest(int64_t id, const std::string& data, bool wallet = false);
    WidgetRequestResponder responder(int64_t id, bool wallet);
    void answerRequest(int64_t id, const std::optional<WidgetRequestOutcome>& outcome, bool wallet = false);
    std::string refusal(int64_t id, std::string_view error, std::string_view reason, const std::string& detail);
    void putData(Json& event, const sdk::CheckedWidgetData& checked);
    std::string syncQuery(const std::string& json);
    void kill(const std::string& reason);
    void handleError(const std::string& text, const std::string& where);
    void drainJobs();
    std::optional<std::string> callTw(const std::string& kind, const std::string& arg);
    void settle();
    void evalScript(const std::string& code, const std::string& filename, bool asModule = false);
    std::string evalBundle(const std::string& source, const std::vector<uint8_t>& bytecode, const std::string& filename);
    void pumpScripts();
    void resetDeadline();
    void task(const std::string& kind, double sliceMs, const std::function<void()>& body);
    void postStats(bool force);
    static double nowMs();
    static double nowSeconds();

    JSThread thread_;
    SandboxEngineDelegate* delegate_ = nullptr;
    std::string html_;
    SandboxConfig config_;
    std::shared_ptr<SandboxResources> resources_;
    std::shared_ptr<IntlProvider> intl_;
    std::unique_ptr<QuickJSContext> js_;
    bool dead_ = false;
    bool started_ = false;
    SandboxStats stats_;
    std::map<int, std::shared_ptr<JSThread::Scheduled>> timers_;
    std::optional<std::vector<uint8_t>> lastBinary_;
    double lastStatsPost_ = 0;
    double lastMemCheck_ = 0;
    bool timerLimitReported_ = false;
    static constexpr double kMaxTimerDelaySeconds = 86400;

    bool visible_;
    bool frameWanted_ = false;
    std::vector<Deferred> deferred_;
    std::shared_ptr<JSThread::Scheduled> wakeTimer_;
    double wakeAt_ = 0;
    double hiddenTickAt_ = 0;

    CpuBudget budget_;
    CpuBudget mainBudget_;
    double currentSliceMs_ = 0;
    std::string currentKind_ = "boot";
    double excludeMs_ = 0;
    double taskQueryMs_ = 0;
    int taskQueries_ = 0;
    double taskOpsCostMs_ = 0;
    std::string_view queryLimitHit_;
    double lastThrottleDiag_ = -1e300;
    std::map<std::string, int> throttledSinceDiag_;
    std::vector<std::pair<std::string, std::string>> pendingBeforeBoot_;
    std::shared_ptr<JSThread::Scheduled> trailingStats_;

    int activeGesture_ = 0;
    int consumedGesture_ = 0;
    int blockedBridgeReported_ = 0;
    int rejectedBridgeReported_ = 0;
    int rejectedStorageReported_ = 0;
    sdk::HapticGate haptics_;
    void rejectStorage(const std::string& detail);
    sdk::RequestGate requests_;
    struct HeldRequest {
        int64_t id;
        std::string data;
    };
    std::optional<HeldRequest> heldRequest_;
    int blockedRequestsReported_ = 0;
    int previewBlockedReported_ = 0;
    std::shared_ptr<Liveness> liveness_ = std::make_shared<Liveness>();
};

}

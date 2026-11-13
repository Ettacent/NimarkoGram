/* Modifications Copyright (C) 2026 Ettacent */

#include "twidget-bridge.h"
#include "backend.h"
#include "display_list.h"
#include "renderer.h"
#include "twidget/widget_host.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <jni.h>
#include <map>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string_view>
#include <unordered_map>
#ifdef __ANDROID__
#include <android/trace.h>
#endif

namespace {
std::atomic<bool> conformanceReports{false};
std::mutex limitsMutex;
std::string limitsOverride;
std::atomic<bool> attackScan{false};

bool mentionsNetwork(std::string l) {
    std::transform(l.begin(), l.end(), l.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    return l.find("127.0.0.1") != std::string::npos || l.find("http://") != std::string::npos ||
           l.find("https://") != std::string::npos || l.find("ws://") != std::string::npos ||
           l.find("wss://") != std::string::npos || l.rfind("//", 0) == 0;
}

void scanCss(const std::string &where, const std::string &css, std::vector<std::string> &leaks) {
    std::string l = css;
    std::transform(l.begin(), l.end(), l.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    if (l.find("@import") != std::string::npos)
        leaks.push_back(where + ": @import survived: " + css.substr(0, 160));
    static const char *const fns[] = {"url(", "image-set(", "image(", "src(", "cross-fade("};
    for (const char *fn : fns) {
        size_t len = std::strlen(fn);
        for (size_t pos = l.find(fn); pos != std::string::npos; pos = l.find(fn, pos + len)) {
            if (pos > 0 && (std::isalnum((unsigned char)l[pos - 1]) || l[pos - 1] == '-'))
                continue;
            size_t a = pos + len;
            while (a < l.size() && (l[a] == ' ' || l[a] == '\t' || l[a] == '\n' || l[a] == '"' || l[a] == '\''))
                a++;
            std::string arg = l.substr(a, 12);
            if (arg.rfind("data:", 0) == 0 || arg.rfind("#", 0) == 0)
                continue;
            if (std::strcmp(fn, "image-set(") == 0 && (arg.rfind("url(", 0) == 0 || arg.rfind("image(", 0) == 0))
                continue;
            leaks.push_back(where + ": " + css.substr(pos, 160));
        }
    }
}

void scanOp(const twidget::Json &op, std::vector<std::string> &leaks) {
    const std::string &kind = op["op"].asString();
    auto attr = [&](const std::string &where, const std::string &value) {
        if (value.rfind("data:", 0) != 0 && mentionsNetwork(value))
            leaks.push_back(where + ": " + value.substr(0, 160));
    };
    if (kind == "el") {
        for (const auto &pair : op["attrs"].arr()) {
            const std::string &name = pair[size_t(0)].asString();
            if (name == "style")
                scanCss("el.style", pair[size_t(1)].asString(), leaks);
            else
                attr("el." + name, pair[size_t(1)].asString());
        }
    } else if (kind == "attr") {
        if (op["name"].asString() == "style")
            scanCss("attr style", op["value"].asString(), leaks);
        else
            attr("attr " + op["name"].asString(), op["value"].asString());
    } else if (kind == "style") {
        scanCss("style " + op["name"].asString(), op["value"].asString(), leaks);
    } else if (kind == "css") {
        scanCss("css", op["text"].asString(), leaks);
    } else if (kind == "canvas") {
        for (const auto &cmd : op["cmds"].arr())
            for (const auto &a : cmd.arr())
                if (a.isString()) {
                    scanCss("canvas", a.asString(), leaks);
                    attr("canvas", a.asString());
                }
    }
}
JavaVM *vm = nullptr;

struct TraceScope {
    explicit TraceScope(const char *name) {
#ifdef __ANDROID__
        ATrace_beginSection(name);
#endif
    }
    ~TraceScope() {
#ifdef __ANDROID__
        ATrace_endSection();
#endif
    }
};

struct ThreadEnv {
    JNIEnv *env = nullptr;
    bool attached = false;
    ThreadEnv() {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
#else
            attached = vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
#endif
        }
    }
    ~ThreadEnv() {
        if (attached)
            vm->DetachCurrentThread();
    }
};
JNIEnv *env() {
    thread_local ThreadEnv thread;
    return thread.env;
}

std::string bytes(JNIEnv *e, jbyteArray value) {
    if (!value)
        return {};
    std::string result(e->GetArrayLength(value), '\0');
    e->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
jbyteArray bytes(JNIEnv *e, const std::string &value) {
    jbyteArray result = e->NewByteArray(value.size());
    if (result)
        e->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
void check(JNIEnv *e) {
    if (!e->ExceptionCheck())
        return;
    e->ExceptionDescribe();
    e->ExceptionClear();
    throw std::runtime_error("Android renderer callback failed");
}

void putFloat(std::vector<jint> &out, float value) {
    jint bits;
    std::memcpy(&bits, &value, sizeof(bits));
    out.push_back(bits);
}

jint argbOf(const twlite::Color &c) {
    return jint((uint32_t(c.a) << 24) | (uint32_t(c.r) << 16) | (uint32_t(c.g) << 8) | c.b);
}

void putGeometry(std::vector<jint> &out, const twlite::Geometry &path) {
    out.push_back(jint(path.ops.size()));
    out.push_back(jint(path.points.size()));
    for (auto op : path.ops)
        out.push_back(jint(op));
    for (float point : path.points)
        putFloat(out, point);
}

void putBytes(std::vector<jint> &out, const uint8_t *bytes, size_t size) {
    out.push_back(jint(size));
    size_t at = out.size();
    out.resize(at + (size + 3) / 4, 0);
    if (size)
        std::memcpy(out.data() + at, bytes, size);
}

void encodeCanvasOp(std::vector<jint> &out, const twlite::CanvasOp &op) {
    using Op = twlite::CanvasOp;
    out.push_back(jint(op.kind));
    if (op.kind == Op::Reset)
        return;
    if (op.kind == Op::PutImage) {
        putFloat(out, op.dx);
        putFloat(out, op.dy);
        out.push_back(op.w);
        out.push_back(op.h);
        putBytes(out, op.rgba ? op.rgba->data() : nullptr, op.rgba ? op.rgba->size() : 0);
        return;
    }
    out.push_back(op.clips ? jint(op.clips->size()) : 0);
    if (op.clips) {
        for (const auto &clip : *op.clips) {
            putGeometry(out, clip.path);
            out.push_back(clip.evenodd);
        }
    }
    if (op.kind == Op::Fill || op.kind == Op::Stroke || op.kind == Op::Clear)
        putGeometry(out, op.path);
    if (op.kind == Op::Clear)
        return;
    out.push_back(op.evenodd);
    for (float value : op.matrix)
        putFloat(out, value);
    putFloat(out, op.alpha);
    out.push_back(jint(op.composite));
    out.push_back(op.has_shadow());
    if (op.has_shadow()) {
        out.push_back(argbOf(op.shadow_color));
        putFloat(out, op.shadow_x);
        putFloat(out, op.shadow_y);
        putFloat(out, op.shadow_blur);
    }
    if (op.kind == Op::Fill || op.kind == Op::Stroke || op.kind == Op::Text) {
        out.push_back(jint(op.paint.kind));
        if (op.paint.kind == twlite::CanvasPaint::Solid) {
            out.push_back(argbOf(op.paint.color));
        } else {
            for (float value : op.paint.args)
                putFloat(out, value);
            out.push_back(jint(op.paint.stops.size()));
            for (const auto &stop : op.paint.stops) {
                putFloat(out, stop.offset);
                out.push_back(argbOf(stop.color));
            }
        }
    }
    if (op.kind == Op::Text) {
        out.push_back(op.font);
        putBytes(out, reinterpret_cast<const uint8_t *>(op.text.data()), op.text.size());
        for (float value : {op.x, op.y, op.scale_x, op.text_dx})
            putFloat(out, value);
        out.push_back(op.stroke_text);
    }
    if (op.kind == Op::Stroke || (op.kind == Op::Text && op.stroke_text)) {
        putFloat(out, op.line_width);
        out.push_back(op.line_cap);
        out.push_back(op.line_join);
        putFloat(out, op.miter_limit);
        out.push_back(jint(op.dashes.size()));
        for (float dash : op.dashes)
            putFloat(out, dash);
        putFloat(out, op.dash_offset);
    }
    if (op.kind == Op::Image) {
        out.push_back(op.canvas);
        out.push_back(op.image);
        for (float value : {op.sx, op.sy, op.sw, op.sh, op.dx, op.dy, op.dw, op.dh})
            putFloat(out, value);
        out.push_back(op.smoothing);
    }
}

std::string encodeDisplayList(const twlite::DisplayList &list) {
    using twlite::Cmd;
    std::vector<jint> out;
    out.reserve(list.size() * 10 + 8);
    out.insert(out.end(), {jint(0x4C445754), 1, jint(list.size()), 0, 0});
    std::vector<const std::string *> strings;
    std::unordered_map<std::string_view, jint> known;
    bool backdrop = false;
    auto stops = [&](const std::vector<twlite::Stop> &list) {
        out.push_back(jint(list.size()));
        for (const auto &stop : list) {
            putFloat(out, stop.offset);
            out.push_back(argbOf(stop.color));
        }
    };
    auto geometry = [&](const twlite::Path &path) {
        out.push_back(jint(path.ops.size()));
        out.push_back(jint(path.points.size()));
        size_t at = out.size();
        out.resize(at + (path.ops.size() + 3) / 4, 0);
        auto *bytes = reinterpret_cast<uint8_t *>(out.data() + at);
        for (size_t i = 0; i < path.ops.size(); i++)
            bytes[i] = uint8_t(path.ops[i]);
        for (float point : path.points)
            putFloat(out, point);
        out.push_back(path.evenodd);
    };
    auto gradient = [&](int kind, const float *args, const std::vector<twlite::Stop> &list) {
        out.push_back(kind);
        for (int i = 0; i < 7; i++)
            putFloat(out, args[i]);
        stops(list);
    };
    for (const auto &c : list) {
        bool rect = c.kind != Cmd::ClipPop && c.kind != Cmd::GroupPop && c.kind != Cmd::FilterPop &&
                    c.kind != Cmd::Text && c.kind != Cmd::Path;
        bool radii = c.radii.any();
        out.push_back(jint(int(c.kind) | ((rect ? 1 : 0) | (radii ? 2 : 0) | (c.has_clip ? 4 : 0)) << 8));
        if (rect)
            for (float v : {c.rect.x, c.rect.y, c.rect.w, c.rect.h})
                putFloat(out, v);
        if (radii)
            for (float v : {c.radii.tlx, c.radii.tly, c.radii.trx, c.radii.try_, c.radii.brx, c.radii.bry, c.radii.blx,
                            c.radii.bly})
                putFloat(out, v);
        if (c.has_clip)
            for (float v : {c.clip.x, c.clip.y, c.clip.w, c.clip.h})
                putFloat(out, v);
        switch (c.kind) {
        case Cmd::FillRect:
        case Cmd::FillEllipse:
            out.push_back(argbOf(c.color));
            break;
        case Cmd::StrokeRect:
        case Cmd::StrokeEllipse:
            out.push_back(argbOf(c.color));
            putFloat(out, c.width);
            break;
        case Cmd::Text: {
            out.push_back(argbOf(c.color));
            out.push_back(c.font);
            putFloat(out, c.x);
            putFloat(out, c.y);
            auto found = known.find(c.text);
            if (found == known.end()) {
                found = known.emplace(std::string_view(c.text), jint(strings.size())).first;
                strings.push_back(&c.text);
            }
            out.push_back(found->second);
            break;
        }
        case Cmd::Image:
            out.push_back(c.canvas ? 1 : 0);
            out.push_back(c.canvas ? c.canvas : c.image);
            break;
        case Cmd::Linear:
        case Cmd::Radial:
        case Cmd::Conic:
            for (float v : {c.ax, c.ay, c.bx, c.by})
                putFloat(out, v);
            stops(c.stops);
            break;
        case Cmd::ClipPush:
            out.push_back(c.path ? 1 : 0);
            if (c.path)
                geometry(*c.path);
            break;
        case Cmd::Path: {
            out.push_back(c.path ? 1 : 0);
            if (!c.path)
                break;
            const twlite::Path &p = *c.path;
            geometry(p);
            out.push_back((p.fill ? 1 : 0) | (p.stroke ? 2 : 0));
            if (p.fill) {
                if (p.gradient != 0 && p.stops.size() >= 2) {
                    const float args[] = {p.x1, p.y1, p.x2, p.y2, p.r, p.fx, p.fy};
                    gradient(p.gradient, args, p.stops);
                } else {
                    out.push_back(0);
                    out.push_back(argbOf(p.fill_color));
                }
            }
            if (p.stroke) {
                out.push_back(argbOf(p.stroke_color));
                if (p.stroke_gradient != 0 && p.stroke_stops.size() >= 2)
                    gradient(p.stroke_gradient, p.sg, p.stroke_stops);
                else
                    out.push_back(0);
                putFloat(out, p.stroke_width);
                out.push_back(p.line_cap);
                out.push_back(p.line_join);
                putFloat(out, p.miter_limit);
                out.push_back(jint(p.dashes.size()));
                for (float dash : p.dashes)
                    putFloat(out, dash);
                putFloat(out, p.dash_offset);
            }
            break;
        }
        case Cmd::GroupPush:
            for (float v : c.matrix)
                putFloat(out, v);
            putFloat(out, c.opacity);
            out.push_back(c.hidden);
            break;
        case Cmd::Shadow:
            out.push_back(argbOf(c.color));
            for (float v : {c.ax, c.ay, c.bx, c.by})
                putFloat(out, v);
            out.push_back(c.inset);
            break;
        case Cmd::FilterPush:
        case Cmd::Backdrop:
            backdrop |= c.kind == Cmd::Backdrop;
            out.push_back(c.filter ? jint(c.filter->size()) : 0);
            if (c.filter) {
                for (const auto &f : *c.filter) {
                    out.push_back(jint(f.kind));
                    putFloat(out, f.amount);
                    putFloat(out, f.x);
                    putFloat(out, f.y);
                    out.push_back(argbOf(f.color));
                }
            }
            break;
        case Cmd::ClipPop:
        case Cmd::GroupPop:
        case Cmd::FilterPop:
            break;
        }
    }
    out[3] = backdrop ? 1 : 0;
    out[4] = jint(out.size());
    out.push_back(jint(strings.size()));
    for (const std::string *text : strings)
        putBytes(out, reinterpret_cast<const uint8_t *>(text->data()), text->size());
    return std::string(reinterpret_cast<const char *>(out.data()), out.size() * sizeof(jint));
}

class AndroidBackend final : public twlite::Backend {
  public:
    AndroidBackend(JNIEnv *e, jobject value) : object_(e->NewGlobalRef(value)) {
        jclass type = e->GetObjectClass(value);
        font_ = e->GetMethodID(type, "createFont", "([BFIZ[B)[F");
        delete_ = e->GetMethodID(type, "deleteFont", "(I)V");
        width_ = e->GetMethodID(type, "textWidth", "(I[B)F");
        image_ = e->GetMethodID(type, "loadImage", "(I[B)[F");
        record_ = e->GetMethodID(type, "record", "([B[BDIIFFII)V");
        signal_ = e->GetMethodID(type, "signal", "(I[B)V");
        intl_ = e->GetMethodID(type, "formatIntl", "([B)[B");
        canvas_run_ = e->GetMethodID(type, "canvasRun", "(III[I)V");
        canvas_read_ = e->GetMethodID(type, "canvasRead", "(IIIII)[B");
        canvas_release_ = e->GetMethodID(type, "canvasRelease", "(I)V");
        layer_ = e->GetMethodID(type, "recordLayer", "(II[B)V");
        text_ = e->GetMethodID(type, "selectableText", "([B)V");
        e->DeleteLocalRef(type);
        check(e);
    }
#ifdef WIDGET_SANDBOX_TESTING
    jobject test_object(JNIEnv *e) { return e->NewLocalRef(object_); }
#endif
    ~AndroidBackend() override { env()->DeleteGlobalRef(object_); }
    int create_font(const std::string &families, float size, int weight, bool italic,
                    const std::string &features, twlite::FontMetrics &out) override {
        auto *e = env();
        auto text = bytes(e, families);
        auto settings = bytes(e, features);
        auto values = static_cast<jfloatArray>(
            e->CallObjectMethod(object_, font_, text, size, weight, jboolean(italic), settings));
        e->DeleteLocalRef(text);
        e->DeleteLocalRef(settings);
        check(e);
        if (!values)
            return 0;
        jfloat data[6] = {};
        e->GetFloatArrayRegion(values, 0, 6, data);
        e->DeleteLocalRef(values);
        out = {data[1], data[2], data[3], data[4], data[5]};
        return int(data[0]);
    }
    void delete_font(int id) override {
        auto *e = env();
        e->CallVoidMethod(object_, delete_, id);
        if (e->ExceptionCheck()) {
            e->ExceptionDescribe();
            e->ExceptionClear();
        }
    }
    float text_width(int id, const std::string &value) override {
        auto *e = env();
        auto text = bytes(e, value);
        float result = e->CallFloatMethod(object_, width_, id, text);
        e->DeleteLocalRef(text);
        check(e);
        return result;
    }
    bool load_image(int id, const std::string &url, float &w, float &h) override {
        if (attackScan && url.rfind("data:", 0) != 0)
            signal(10, "renderer asked for " + url.substr(0, 160));
        auto *e = env();
        auto text = bytes(e, url);
        auto values = static_cast<jfloatArray>(e->CallObjectMethod(object_, image_, id, text));
        e->DeleteLocalRef(text);
        check(e);
        if (!values)
            return false;
        jfloat data[2] = {};
        e->GetFloatArrayRegion(values, 0, 2, data);
        e->DeleteLocalRef(values);
        w = data[0];
        h = data[1];
        return w > 0 && h > 0;
    }
    std::string intl(const std::string &request) {
        auto *e = env();
        auto text = bytes(e, request);
        auto result = static_cast<jbyteArray>(e->CallObjectMethod(object_, intl_, text));
        e->DeleteLocalRef(text);
        check(e);
        auto value = bytes(e, result);
        if (result)
            e->DeleteLocalRef(result);
        return value;
    }
    void signal(int kind, const std::string &value = {}) {
        auto *e = env();
        auto text = bytes(e, value);
        e->CallVoidMethod(object_, signal_, kind, text);
        e->DeleteLocalRef(text);
        check(e);
    }
    void canvas_run(int id, int w, int h, const std::vector<twlite::CanvasOp> &ops) override {
        auto *e = env();
        canvasWords_.clear();
        for (const auto &op : ops)
            encodeCanvasOp(canvasWords_, op);
        auto data = e->NewIntArray(jsize(canvasWords_.size()));
        if (!data) {
            check(e);
            return;
        }
        e->SetIntArrayRegion(data, 0, jsize(canvasWords_.size()), canvasWords_.data());
        e->CallVoidMethod(object_, canvas_run_, id, w, h, data);
        e->DeleteLocalRef(data);
        check(e);
    }
    bool canvas_read(int id, int x, int y, int w, int h, std::vector<uint8_t> &rgba) override {
        auto *e = env();
        auto data = static_cast<jbyteArray>(e->CallObjectMethod(object_, canvas_read_, id, x, y, w, h));
        check(e);
        if (!data)
            return false;
        size_t size = e->GetArrayLength(data);
        bool ok = w > 0 && h > 0 && size == size_t(w) * size_t(h) * 4;
        if (ok) {
            rgba.resize(size);
            e->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(rgba.data()));
        }
        e->DeleteLocalRef(data);
        return ok;
    }
    void canvas_release(int id) override {
        auto *e = env();
        e->CallVoidMethod(object_, canvas_release_, id);
        if (e->ExceptionCheck()) {
            e->ExceptionDescribe();
            e->ExceptionClear();
        }
    }
    void selectable_text(const std::string &runs) {
        auto *e = env();
        auto text = bytes(e, runs);
        e->CallVoidMethod(object_, text_, text);
        e->DeleteLocalRef(text);
        check(e);
    }
    void layer(int index, int version, const std::string &list) {
        auto *e = env();
        auto text = bytes(e, list);
        e->CallVoidMethod(object_, layer_, index, version, text);
        e->DeleteLocalRef(text);
        check(e);
    }
    void record(const std::string &list, const std::string &composition, double clock, int w, int h, float dpr,
                const twlite::BuildStats &stats) {
        auto *e = env();
        auto text = bytes(e, list);
        auto layers = bytes(e, composition);
        e->CallVoidMethod(object_, record_, text, layers, clock, w, h, dpr, float(stats.parse_ms), stats.passes,
                          stats.builds);
        e->DeleteLocalRef(text);
        e->DeleteLocalRef(layers);
        check(e);
    }

  private:
    jobject object_;
    std::vector<jint> canvasWords_;
    jmethodID font_, delete_, width_, image_, record_, signal_, intl_, canvas_run_, canvas_read_, canvas_release_, text_,
        layer_;
};

class AndroidIntl final : public twidget::IntlProvider {
  public:
    explicit AndroidIntl(std::shared_ptr<AndroidBackend> backend) : backend_(std::move(backend)) {}
    std::string format(const twidget::Json &request) override {
        std::string answer;
        try {
            answer = backend_->intl(request.dump());
        } catch (...) {
        }
        return answer.empty() ? basic_.format(request) : answer;
    }

  private:
    std::shared_ptr<AndroidBackend> backend_;
    twidget::BasicIntl basic_;
};

class AndroidRenderer final : public twidget::Renderer {
  public:
    AndroidRenderer(std::shared_ptr<AndroidBackend> backend, const std::string &theme, float width, float dpr)
        : backend_(std::move(backend)), document_(width, dpr, theme == "dark", *backend_) {
        document_.set_compositing(true);
    }
    void load() override {
        guard([&] {
            if (onMessage)
                onMessage("{\"type\":\"ready\"}");
            dirty();
        });
    }
    void send(const std::string &json) override {
        guard([&] {
            auto message = twidget::Json::parse(json);
            if (!message)
                return;
            if ((*message)["type"].asString() == "requestFrame") {
                backend_->signal(1);
                return;
            }
            if ((*message)["type"].asString() == "frozen")
                frozen_ = true;
            if (conformanceReports)
                reportConformance(*message);
            TraceScope trace("WidgetSandbox:ReceiveLayout");
            double started = nowMs();
            if (frameSentAt_ > 0 && !(*message)["ops"].arr().empty()) {
                opsLatencyMs_ += started - frameSentAt_;
                opsLatencies_++;
                frameSentAt_ = 0;
            }
            dispatch(document_.receive(*message));
            receiveMs_ += nowMs() - started;
            dirty();
        });
    }
    void reportConformance(const twidget::Json &message) {
        const auto &ops = message["ops"].arr();
        if (!ops.empty())
            backend_->signal(17, std::to_string(ops.size()));
        for (const auto &op : ops) {
            for (const char *name : {"data-conformance", "data-stress"}) {
                const twidget::Json *value = nullptr;
                if (op["name"].asString() == name)
                    value = op.find("value");
                else if (const auto *attrs = op.find("attrs"))
                    for (const auto &kv : attrs->arr())
                        if (kv[size_t(0)].asString() == name)
                            value = &kv[size_t(1)];
                if (value && value->isString())
                    backend_->signal(name[5] == 'c' ? 9 : 16, value->asString());
            }
        }
    }
    void setTheme(const std::string &theme) override {
        guard([&] {
            std::vector<twidget::Json> out;
            document_.set_theme(theme == "dark", out);
            dispatch(out);
            dirty();
        });
    }
    void setWidth(double width) override {
        guard([&] {
            std::vector<twidget::Json> out;
            TraceScope trace("WidgetSandbox:ResizeLayout");
            document_.set_width(width, out);
            dispatch(out);
            dirty();
        });
    }
    void destroy() override { destroyed_ = true; }
    void repaint() {
        guard([&] {
            repaintNeeded_ = true;
            layerVersions_.clear();
            backend_->signal(2);
        });
    }
    void touch(int action, float x, float y, int pointer, bool primary, bool mouse, int modifiers, int button) {
        guard([&] {
            dispatch(document_.touch(action, x, y, pointer, primary, mouse, modifiers, button));
            if (primary && (action == 2 || action == 3) && onMessage)
                onMessage("{\"type\":\"release\"}");
            if (mouse)
                cursor();
            dirty();
        });
    }
    void hover(int action, float x, float y, int modifiers) {
        guard([&] {
            dispatch(document_.hover(action, x, y, modifiers));
            cursor();
            dirty();
        });
    }
    void tick(float ms, bool frame, bool animate, bool paint) {
        guard([&] {
            ticks_++;
            if (frame && !frozen_ && onMessage) {
                frameSentAt_ = nowMs();
                framesSent_++;
                onMessage(twidget::Json::object().set("type", "frame").set("time", double(ms)).dump());
            }
            if (animate) {
                TraceScope trace("WidgetSandbox:Animate");
                double started = nowMs();
                dispatch(document_.tick());
                animateMs_ += nowMs() - started;
                dirty();
            }
            if (!paint || (!document_.dirty() && !repaintNeeded_))
                return;
            if (recording_) {
                paintWaiting_ = true;
                return;
            }
            TraceScope trace("WidgetSandbox:Paint");
            double paintStarted = nowMs();
            auto job = std::make_shared<RecordJob>();
            job->list = document_.paint();
            job->paintMs = nowMs() - paintStarted;
            const twlite::Composition &composition = document_.composition();
            job->composition.version = composition.version;
            job->composition.layers.resize(composition.layers.size());
            std::unordered_map<int64_t, uint64_t> versions;
            for (size_t i = 0; i < composition.layers.size(); i++) {
                const auto &layer = composition.layers[i];
                auto &copy = job->composition.layers[i];
                copy.id = layer.id;
                copy.bounds = layer.bounds;
                copy.content_version = layer.content_version;
                copy.raster_scale = layer.raster_scale;
                copy.has_clip = layer.has_clip;
                copy.clip = layer.clip;
                copy.clip_radii = layer.clip_radii;
                copy.track_version = layer.track_version;
                copy.track = layer.track;
                copy.parent = layer.parent;
                versions[layer.id] = layer.content_version;
                auto known = layerVersions_.find(layer.id);
                if (known == layerVersions_.end() || known->second != layer.content_version) {
                    copy.content = layer.content;
                    job->layers.push_back(int(i));
                }
            }
            layerVersions_ = std::move(versions);
            if (document_.layouts() != textLayouts_) {
                textLayouts_ = document_.layouts();
                std::string text = document_.selectable_text_json();
                if (text != lastText_) {
                    lastText_ = text;
                    job->text = std::move(text);
                    job->hasText = true;
                }
            }
            job->clock = twlite::Renderer::clock();
            job->width = document_.px_width();
            job->height = document_.px_height();
            job->dpr = document_.dpr();
            job->stats = document_.stats();
            if (conformanceReports) {
                job->work = twidget::Json::object()
                                .set("receive", receiveMs_)
                                .set("animate", animateMs_)
                                .set("paint", job->paintMs)
                                .set("ticks", double(ticks_))
                                .set("framesSent", double(framesSent_))
                                .set("opsLatency", opsLatencies_ ? opsLatencyMs_ / opsLatencies_ : -1.0);
            }
            receiveMs_ = animateMs_ = opsLatencyMs_ = 0;
            ticks_ = framesSent_ = opsLatencies_ = 0;
            recording_ = true;
            recorder_.async([this, job] { record(*job); });
            repaintNeeded_ = false;
        });
        try {
            backend_->signal(6);
        } catch (...) {
        }
    }

  private:
    struct RecordJob {
        twlite::DisplayList list;
        twlite::Composition composition;
        std::vector<int> layers;
        std::string text;
        bool hasText = false;
        double clock = 0, paintMs = 0;
        int width = 0, height = 0;
        float dpr = 1;
        twlite::BuildStats stats;
        twidget::Json work;
    };
    void record(RecordJob &job) {
        if (!destroyed_) {
            try {
                TraceScope trace("WidgetSandbox:Record");
                double serializeMs = 0;
                for (int i : job.layers) {
                    const auto &layer = job.composition.layers[size_t(i)];
                    double started = nowMs();
                    std::string content = encodeDisplayList(layer.content);
                    serializeMs += nowMs() - started;
                    backend_->layer(i, int(layer.content_version), content);
                }
                if (job.hasText)
                    backend_->selectable_text(job.text);
                double started = nowMs();
                std::string listBytes = encodeDisplayList(job.list), compositionJson = twlite::to_json(job.composition);
                serializeMs += nowMs() - started;
                if (job.work.isObject())
                    backend_->signal(18, job.work.set("serialize", serializeMs).dump());
                backend_->record(listBytes, compositionJson, job.clock, job.width, job.height, job.dpr, job.stats);
            } catch (const std::exception &error) {
                if (onDiag)
                    onDiag("android-renderer", error.what());
            }
        }
        recording_ = false;
        if (paintWaiting_.exchange(false) && !destroyed_) {
            try {
                backend_->signal(2);
            } catch (...) {
            }
        }
    }

    template <class F> void guard(F body) {
        if (destroyed_)
            return;
        try {
            body();
        } catch (const std::exception &error) {
            if (onDiag)
                onDiag("android-renderer", error.what());
        }
    }
    void dirty() {
        if (document_.dirty())
            backend_->signal(2);
        schedule();
    }
    void schedule() {
        double delay = -1;
        if (document_.wants_tick())
            delay = 0;
        else {
            double next = document_.next_tick_in();
            if (std::isfinite(next))
                delay = std::max(1.0, next * 1000);
        }
        if (delay == scheduled_ && delay != 0)
            return;
        if (delay == 0 && scheduled_ == 0)
            return;
        scheduled_ = delay;
        backend_->signal(7, std::to_string(delay));
    }
    void cursor() {
        auto now = document_.cursor();
        if (now == cursor_)
            return;
        cursor_ = now;
        backend_->signal(11, now);
    }
    void dispatch(const std::vector<twidget::Json> &output) {
        for (const auto &message : output) {
            auto kind = message["type"].asString();
            if (kind == "violation" && onDiag)
                onDiag("renderer-violation", message["detail"].asString());
            if (!onMessage)
                continue;
            onMessage(message.dump());
        }
    }
    std::shared_ptr<AndroidBackend> backend_;
    twlite::Renderer document_;
    std::unordered_map<int64_t, uint64_t> layerVersions_;
    uint64_t textLayouts_ = ~0ull;
    std::string lastText_ = "[]";
    std::string cursor_ = "auto";
    double scheduled_ = -1;
    double receiveMs_ = 0, animateMs_ = 0;
    double frameSentAt_ = 0, opsLatencyMs_ = 0;
    int ticks_ = 0, framesSent_ = 0, opsLatencies_ = 0;
    static double nowMs() {
        return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    std::atomic<bool> destroyed_{false};
    bool frozen_ = false, repaintNeeded_ = false;
    std::atomic<bool> recording_{false}, paintWaiting_{false};
    twidget::JSThread recorder_{"widget-record"};
};

twidget::WidgetTheme parseTheme(const std::string &json) {
    twidget::WidgetTheme theme;
    auto value = twidget::Json::parse(json);
    if (!value)
        return theme;
    theme.colorScheme = (*value)["colorScheme"].asString() == "dark" ? "dark" : "light";
    for (const auto &[key, color] : (*value)["params"].obj())
        if (color.isString())
            theme.params[key] = color.asString();
    const auto &button = (*value)["button"];
    auto text = [&](const char *key) -> std::optional<std::string> {
        const auto &v = button[key];
        return v.isString() ? std::optional<std::string>(v.asString()) : std::nullopt;
    };
    auto number = [&](const char *key) -> std::optional<double> {
        const auto &v = button[key];
        return v.isNumber() ? std::optional<double>(v.asNumber()) : std::nullopt;
    };
    theme.button.bg = text("bg");
    theme.button.text = text("text");
    theme.button.radius = text("radius");
    theme.button.font = text("font");
    theme.button.padding = text("padding");
    theme.button.height = text("height");
    theme.button.pressedOpacity = number("pressedOpacity");
    theme.button.disabledOpacity = number("disabledOpacity");
    const auto &text_tokens = (*value)["text"];
    if (text_tokens["fontSize"].isNumber())
        theme.text.fontSize = text_tokens["fontSize"].asNumber();
    if (text_tokens["lineHeight"].isNumber())
        theme.text.lineHeight = text_tokens["lineHeight"].asNumber();
    if (text_tokens["padding"].isString())
        theme.text.padding = text_tokens["padding"].asString();
    return theme;
}
twidget::WidgetData parseData(const twidget::Json &value) {
    twidget::WidgetData data;
    if (value["chatData"].isString())
        data.chatData = value["chatData"].asString();
    if (value["publicData"].isString())
        data.publicData = value["publicData"].asString();
    return data;
}
twidget::WidgetContext parseContext(const twidget::Json &value) {
    twidget::WidgetContext context;
    context.isCopy = value["isCopy"].asBool();
    context.canQuery = value["canQuery"].asBool();
    context.backgroundQuery = value["backgroundQuery"].asBool();
    context.preview = value["preview"].asBool();
    context.wallet = value["wallet"].asBool();
    if (value["chatType"].isString())
        context.chatType = value["chatType"].asString();
    const auto &viewer = value["viewer"];
    if (viewer["id"].isNumber()) {
        twidget::WidgetViewer v;
        v.id = int64_t(viewer["id"].asNumber());
        if (viewer["firstName"].isString())
            v.firstName = viewer["firstName"].asString();
        if (viewer["lastName"].isString())
            v.lastName = viewer["lastName"].asString();
        if (viewer["username"].isString())
            v.username = viewer["username"].asString();
        v.isPremium = viewer["isPremium"].asBool();
        context.viewer = v;
    }
    return context;
}
twidget::WidgetContentSafeArea parseSafeArea(const twidget::Json &value) {
    twidget::WidgetContentSafeArea area;
    auto get = [&](const char *name) { return value[name].isNumber() ? value[name].asNumber() : 0.0; };
    area.top = get("top");
    area.right = get("right");
    area.bottom = get("bottom");
    area.left = get("left");
    area.cornerLeft = get("cornerLeft");
    area.cornerRight = get("cornerRight");
    return area;
}
twidget::WidgetStorage parseStorage(const std::string &json) {
    twidget::WidgetStorage storage;
    auto value = twidget::Json::parse(json);
    if (!value)
        return storage;
    for (const auto &[key, v] : (*value)["local"].obj())
        storage.local[key] = v.asString();
    for (const auto &[key, v] : (*value)["session"].obj())
        storage.session[key] = v.asString();
    return storage;
}

struct Session {
    twidget::JSThread control{"widget-control"};
    std::shared_ptr<AndroidBackend> backend;
    std::unique_ptr<twidget::WidgetHost> host;
    std::atomic<bool> closing{false};
    std::mutex requestsMutex;
    std::unordered_map<int64_t, std::function<void(const twidget::WidgetRequestOutcome &)>> requests;
    int64_t nextRequest = 1;
    void shutdown() {
        if (closing.exchange(true))
            return;
        control.join();
        host.reset();
        backend.reset();
    }
    ~Session() { shutdown(); }
    template <class F> void input(F body) {
        if (closing)
            return;
        control.async([this, body] {
            if (!host || closing)
                return;
            host->onHostThread([&] { body(static_cast<AndroidRenderer &>(host->renderer())); });
        });
    }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> nextId{1};
std::shared_ptr<Session> get(jlong id) {
    std::lock_guard lock(registryMutex);
    auto it = sessions.find(id);
    return it == sessions.end() ? nullptr : it->second;
}
std::mutex resourcesMutex;
std::unordered_map<std::string, std::shared_ptr<twidget::SandboxResources>> resourceCache;
std::shared_ptr<twidget::SandboxResources> resources(const std::string &path) {
    std::lock_guard lock(resourcesMutex);
    auto &value = resourceCache[path];
    if (!value)
        value = twidget::SandboxResources::fromDirectory(path);
    return value;
}
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_registerResources(
    JNIEnv *e, jclass, jbyteArray key, jbyteArray sandbox, jbyteArray validator, jbyteArray renderer,
    jbyteArray sandboxBytecode, jbyteArray validatorBytecode) {
    auto value = std::make_shared<twidget::SandboxResources>();
    value->sandboxBundle = bytes(e, sandbox);
    value->validatorBundle = bytes(e, validator);
    value->rendererBundle = bytes(e, renderer);
    auto binary = [e](jbyteArray array) {
        std::string data = bytes(e, array);
        return std::vector<uint8_t>(data.begin(), data.end());
    };
    value->sandboxBytecode = binary(sandboxBytecode);
    value->validatorBytecode = binary(validatorBytecode);
    std::lock_guard lock(resourcesMutex);
    resourceCache[bytes(e, key)] = std::move(value);
}

extern "C" JNIEXPORT jlong JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_create(
    JNIEnv *e, jclass, jbyteArray path, jbyteArray html, jbyteArray theme, jfloat width, jfloat dpr,
    jint cpuMultiplier, jbyteArray locale, jbyteArray storage, jbyteArray sdk, jbyteArray stoppedLabel,
    jboolean visible, jobject backend) {
    try {
        auto session = std::make_shared<Session>();
        session->backend = std::make_shared<AndroidBackend>(e, backend);
        twidget::WidgetHostOptions options;
        options.visible = visible;
        options.devicePixelRatio = dpr;
        options.features = {"webAnimations"};
        options.locale = bytes(e, locale);
        options.storage = parseStorage(bytes(e, storage));
        if (auto value = twidget::Json::parse(bytes(e, sdk))) {
            options.data = parseData((*value)["data"]);
            options.context = parseContext((*value)["context"]);
            options.contentSafeArea = parseSafeArea((*value)["contentSafeArea"]);
        }
        options.cpuLimitMs *= cpuMultiplier;
        options.cpuQuotaMs *= cpuMultiplier;
        options.mainThreadQuotaMs *= cpuMultiplier;
        {
            std::lock_guard lock(limitsMutex);
            if (auto limits = twidget::Json::parse(limitsOverride); limits && limits->isObject()) {
                const auto &l = *limits;
                if (l["policy"].isString())
                    options.policy = l["policy"].asString();
                if (l["cpuQuotaMs"].isNumber())
                    options.cpuQuotaMs = l["cpuQuotaMs"].asNumber();
                if (l["cpuQuotaWindowMs"].isNumber())
                    options.cpuQuotaWindowMs = l["cpuQuotaWindowMs"].asNumber();
                if (l["mainThreadQuotaMs"].isNumber())
                    options.mainThreadQuotaMs = l["mainThreadQuotaMs"].asNumber();
                if (l["maxTimers"].isNumber())
                    options.maxTimers = l["maxTimers"].asInt();
                if (l["memoryLimitBytes"].isNumber())
                    options.memoryLimitBytes = size_t(l["memoryLimitBytes"].asNumber());
            }
        }
        auto factory = [b = session->backend, dpr](auto, const std::string &theme, double w) {
            return std::make_unique<AndroidRenderer>(b, theme, float(w), dpr);
        };
        session->host = std::make_unique<twidget::WidgetHost>(
            bytes(e, html), parseTheme(bytes(e, theme)), width, options, resources(bytes(e, path)), factory,
            std::make_shared<AndroidIntl>(session->backend));
        session->host->stoppedLabel = bytes(e, stoppedLabel);
        auto target = session->backend;
        session->host->onChange = [target](const twidget::WidgetDiagnostics &d) {
            auto j = twidget::Json::object();
            j.set("loaded", d.loaded)
                .set("rendered", d.rendered)
                .set("nodes", d.nodes)
                .set("jsMs", d.stats.jsMs)
                .set("tasks", d.stats.tasks);
            j.set("queries", d.stats.queries)
                .set("queryMs", d.stats.queryMs)
                .set("throttled", d.stats.throttledTasks);
            j.set("memoryBytes", double(d.stats.memoryBytes)).set("height", d.height);
            j.set("resizeAnchor", d.resizeAnchor);
            j.set("dead", d.dead.value_or("")).set("error", d.lastError.value_or(""));
            if (!d.diags.empty()) {
                auto last = d.diags.rbegin();
                j.set("diagnostic",
                      last->first + ": " + (last->second.second.empty() ? "" : last->second.second.back()));
            }
            try {
                target->signal(4, j.dump());
            } catch (...) {
            }
        };
        std::weak_ptr<Session> weak = session;
        session->host->onRequest = [target, weak](const twidget::WidgetRequest &request,
                                                  std::function<void(const twidget::WidgetRequestOutcome &)> respond) {
            auto owner = weak.lock();
            if (!owner)
                return;
            int64_t id;
            {
                std::lock_guard lock(owner->requestsMutex);
                id = owner->nextRequest++;
                owner->requests[id] = std::move(respond);
            }
            try {
                target->signal(12, twidget::Json::object()
                                       .set("id", double(id))
                                       .set("data", request.data)
                                       .set("wallet", request.wallet)
                                       .dump());
            } catch (...) {
            }
        };
        session->host->onWallet = [target, weak](const twidget::WalletCall &call,
                                                 std::function<void(const twidget::WidgetRequestOutcome &)> respond) {
            auto owner = weak.lock();
            if (!owner)
                return;
            int64_t id;
            {
                std::lock_guard lock(owner->requestsMutex);
                id = owner->nextRequest++;
                owner->requests[id] = std::move(respond);
            }
            try {
                target->signal(19, twidget::Json::object().set("id", double(id)).set("call", call.toJson()).dump());
            } catch (...) {
            }
        };
        session->host->onCopyText = [target](const std::string &text) {
            try {
                target->signal(13, text);
            } catch (...) {
            }
        };
        session->host->onWatchPublicData = [target](bool on) {
            try {
                target->signal(14, on ? "1" : "0");
            } catch (...) {
            }
        };
        session->host->onAction = [target](const twidget::WidgetAction &action) {
            try {
                target->signal(5, action.toJson().dump());
            } catch (...) {
            }
        };
        session->host->onStorageCommit = [target](const std::string &area,
                                                  const std::map<std::string, std::string> &values) {
            auto content = twidget::Json::object();
            for (const auto &[key, value] : values)
                content.set(key, value);
            try {
                target->signal(8, twidget::Json::object().set("area", area).set("values", content).dump());
            } catch (...) {
            }
        };
        session->host->onGesture = [target](const twidget::WidgetGestureClaim &claim) {
            try {
                target->signal(3, twidget::Json::object().set("x", claim.x).set("y", claim.y).set("hold", claim.hold).dump());
            } catch (...) {
            }
        };
        if (attackScan)
            session->host->onRenderOps = [target](const std::string &clean) {
                std::vector<std::string> leaks;
                if (auto ops = twidget::Json::parse(clean))
                    for (const auto &op : ops->arr())
                        scanOp(op, leaks);
                for (const auto &leak : leaks) {
                    try {
                        target->signal(10, "ops " + leak);
                    } catch (...) {
                    }
                }
            };
        jlong id = nextId.fetch_add(1);
        {
            std::lock_guard lock(registryMutex);
            sessions[id] = session;
        }
        session->host->start();
        return id;
    } catch (const std::exception &error) {
        jclass type = e->FindClass("java/lang/IllegalStateException");
        e->ThrowNew(type, error.what());
        e->DeleteLocalRef(type);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_destroy(JNIEnv *,
                                                                                              jclass,
                                                                                              jlong id) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard lock(registryMutex);
        auto it = sessions.find(id);
        if (it == sessions.end())
            return;
        session = std::move(it->second);
        sessions.erase(it);
    }
    session->shutdown();
}
extern "C" JNIEXPORT void JNICALL
Java_org_telegram_utils_twidget_WidgetSandboxNative_visible(JNIEnv *, jclass, jlong id, jboolean visible) {
    auto s = get(id);
    if (s)
        s->input([owner = s.get(), visible](auto &) { owner->host->setVisible(visible); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_theme(JNIEnv *e, jclass,
                                                                                            jlong id,
                                                                                            jbyteArray theme) {
    auto s = get(id);
    if (s)
        s->input([owner = s.get(), value = parseTheme(bytes(e, theme))](auto &) { owner->host->setTheme(value); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_safeArea(JNIEnv *e, jclass,
                                                                                               jlong id,
                                                                                               jbyteArray area) {
    auto s = get(id);
    if (!s)
        return;
    auto value = twidget::Json::parse(bytes(e, area));
    if (value)
        s->input([owner = s.get(), inset = parseSafeArea(*value)](auto &) { owner->host->setContentSafeArea(inset); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_data(JNIEnv *e, jclass,
                                                                                           jlong id,
                                                                                           jbyteArray data) {
    auto s = get(id);
    if (!s)
        return;
    auto value = twidget::Json::parse(bytes(e, data));
    if (value)
        s->input([owner = s.get(), state = parseData(*value)](auto &) { owner->host->setData(state); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_answerRequest(
    JNIEnv *e, jclass, jlong id, jlong request, jint failure, jbyteArray result) {
    auto s = get(id);
    if (!s)
        return;
    std::function<void(const twidget::WidgetRequestOutcome &)> respond;
    {
        std::lock_guard lock(s->requestsMutex);
        auto it = s->requests.find(request);
        if (it == s->requests.end())
            return;
        respond = std::move(it->second);
        s->requests.erase(it);
    }
    twidget::WidgetRequestOutcome outcome;
    outcome.failure = failure == 1   ? twidget::WidgetRequestFailure::Timeout
                      : failure == 2 ? twidget::WidgetRequestFailure::RateLimited
                      : failure == 3 ? twidget::WidgetRequestFailure::Failed
                      : failure == 4 ? twidget::WidgetRequestFailure::Rejected
                      : failure == 5 ? twidget::WidgetRequestFailure::BadRequest
                                     : twidget::WidgetRequestFailure::None;
    if (outcome.failure == twidget::WidgetRequestFailure::None && result)
        outcome.answer.result = bytes(e, result);
    respond(outcome);
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_width(JNIEnv *, jclass,
                                                                                            jlong id,
                                                                                            jfloat width) {
    auto s = get(id);
    if (s)
        s->input([width](auto &r) { r.setWidth(width); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_tick(
    JNIEnv *, jclass, jlong id, jfloat ms, jboolean frame, jboolean animate, jboolean paint) {
    auto s = get(id);
    if (s)
        s->input([=](auto &r) { r.tick(ms, frame, animate, paint); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_repaint(JNIEnv *,
                                                                                              jclass,
                                                                                              jlong id) {
    auto s = get(id);
    if (s)
        s->input([](auto &r) { r.repaint(); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_touch(
    JNIEnv *, jclass, jlong id, jint action, jfloat x, jfloat y, jint pointer, jboolean primary, jboolean mouse,
    jint modifiers, jint button) {
    auto s = get(id);
    if (s)
        s->input([=](auto &r) { r.touch(action, x, y, pointer, primary, mouse, modifiers, button); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_hover(
    JNIEnv *, jclass, jlong id, jint action, jfloat x, jfloat y, jint modifiers) {
    auto s = get(id);
    if (s)
        s->input([=](auto &r) { r.hover(action, x, y, modifiers); });
}
extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_conformance(JNIEnv *, jclass,
                                                                                                  jboolean enabled) {
    conformanceReports = enabled;
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_limits(JNIEnv *e, jclass,
                                                                                              jbyteArray json) {
    std::lock_guard lock(limitsMutex);
    limitsOverride = json ? bytes(e, json) : std::string();
}

extern "C" JNIEXPORT void JNICALL Java_org_telegram_utils_twidget_WidgetSandboxNative_attackScan(JNIEnv *, jclass,
                                                                                                 jboolean enabled) {
    attackScan = enabled;
}

#ifdef WIDGET_SANDBOX_TESTING
extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_ui_Components_WidgetSandboxTestNative_backend(JNIEnv *e, jclass, jlong id) {
    auto s = get(id);
    return s && !s->closing ? s->backend->test_object(e) : nullptr;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_ui_Components_WidgetSandboxTestNative_probe(JNIEnv *e, jclass, jlong id, jbyteArray code) {
    auto s = get(id);
    return bytes(e, s && !s->closing ? s->host->probe(bytes(e, code)) : "closed");
}
extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_ui_Components_WidgetSandboxTestNative_liveSessions(JNIEnv *, jclass) {
    std::lock_guard lock(registryMutex);
    return sessions.size();
}
#endif

extern "C" bool registerTwidgetNatives(JNIEnv *e) {
    if (!e || e->GetJavaVM(&vm) != JNI_OK)
        return false;
    jclass type = e->FindClass("org/telegram/utils/twidget/WidgetSandboxNative");
    if (!type)
        return false;
#define BIND(name, signature)                                                                                \
    {                                                                                                        \
        const_cast<char *>(#name), const_cast<char *>(signature),                                            \
            reinterpret_cast<void *>(Java_org_telegram_utils_twidget_WidgetSandboxNative_##name)             \
    }
    const JNINativeMethod methods[] = {
        BIND(create, "([B[B[BFFI[B[B[B[BZLjava/lang/Object;)J"),
        BIND(registerResources, "([B[B[B[B[B[B)V"),
        BIND(destroy, "(J)V"),
        BIND(visible, "(JZ)V"),
        BIND(theme, "(J[B)V"),
        BIND(width, "(JF)V"),
        BIND(data, "(J[B)V"),
        BIND(safeArea, "(J[B)V"),
        BIND(answerRequest, "(JJI[B)V"),
        BIND(repaint, "(J)V"),
        BIND(tick, "(JFZZZ)V"),
        BIND(touch, "(JIFFIZZII)V"),
        BIND(hover, "(JIFFI)V"),
        BIND(conformance, "(Z)V"),
        BIND(attackScan, "(Z)V")};
#undef BIND
    int status = e->RegisterNatives(type, methods, sizeof(methods) / sizeof(methods[0]));
    e->DeleteLocalRef(type);
    return status == JNI_OK;
}

/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/gesture_policy.h"
#include "twidget/renderer.h"
#include "twidget/sandbox_engine.h"
#include "twidget/trusted_validator.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace twidget {

struct WidgetButtonTokens {
    std::optional<std::string> bg, text, radius, font, padding, height;
    std::optional<double> pressedOpacity, disabledOpacity;
};

struct WidgetTextTokens {
    std::optional<double> fontSize, lineHeight;
    std::optional<std::string> padding;
};

struct WidgetTheme {
    std::string colorScheme = "light";
    std::map<std::string, std::string> params;
    WidgetButtonTokens button;
    WidgetTextTokens text;
};


struct WidgetHostOptions {
    size_t memoryLimitBytes = size_t(8) << 20;
    double cpuLimitMs = 500;
    double cpuQuotaMs = 300;
    double cpuQuotaWindowMs = 1000;
    double cpuKillAfterMs = 0;
    double mainThreadQuotaMs = 100;
    int maxTimers = 64;
    bool visible = true;
    double devicePixelRatio = 1;
    std::string policy = "strict";
    std::vector<std::string> features;
    std::string locale = std::string(sdk::DEFAULT_LOCALE);
    WidgetContentSafeArea contentSafeArea;
    WidgetStorage storage;
    WidgetData data;
    WidgetContext context;
};

struct WidgetDiagnostics {
    std::vector<std::pair<std::string, std::string>> logs;
    std::map<std::string, std::pair<int, std::vector<std::string>>> diags;
    std::vector<WidgetAction> actions;
    int storageCommits = 0;
    std::vector<std::pair<std::string, std::string>> bridge;
    SandboxStats stats;
    int nodes = 0;
    bool loaded = false;
    std::optional<double> rendererReadyMs;
    std::optional<double> loadedMs;
    bool rendered = false;
    std::optional<double> renderedMs;
    std::optional<std::string> dead;
    std::optional<std::string> lastError;
    double height = 0;
    std::string resizeAnchor = std::string(sdk::DEFAULT_RESIZE_ANCHOR);
    bool watchesPublicData = false;
    std::optional<double> firstContentMs;

    int countDiag(const std::string& kind) const { auto it = diags.find(kind); return it == diags.end() ? 0 : it->second.first; }
    bool hasLog(const std::string& text) const { for (auto& l : logs) if (l.second == text) return true; return false; }
};

class GestureCounter {
public:
    static bool isUserActivation(const Json& ev);
    int gestureFor(const Json& ev);
    void pressBegan() { pressOpen_ = false; lastType_.clear(); }

private:
    int number_ = 0;
    bool pressOpen_ = false;
    std::string lastType_;
};

class WidgetHost final : public SandboxEngineDelegate {
public:
    WidgetHost(std::string html, WidgetTheme theme, double width, WidgetHostOptions options, std::shared_ptr<SandboxResources> resources,
               RendererFactory rendererFactory, std::shared_ptr<IntlProvider> intl = nullptr);
    [[deprecated("pass a WidgetTheme (SDK v1)")]]
    WidgetHost(std::string html, std::string theme, double width, WidgetHostOptions options, std::shared_ptr<SandboxResources> resources,
               RendererFactory rendererFactory, std::shared_ptr<IntlProvider> intl = nullptr)
        : WidgetHost(std::move(html), WidgetTheme{std::move(theme), {}, {}, {}}, width, std::move(options), std::move(resources), std::move(rendererFactory), std::move(intl)) {}
    ~WidgetHost();
    WidgetHost(const WidgetHost&) = delete;
    WidgetHost& operator=(const WidgetHost&) = delete;

    void start();
    void setTheme(const WidgetTheme& theme);
    [[deprecated("pass a WidgetTheme (SDK v1)")]]
    void setTheme(const std::string& theme) { setTheme(WidgetTheme{theme, {}, {}, {}}); }
    void setVisible(bool visible);
    void setData(const WidgetData& data);
    void setContentSafeArea(const WidgetContentSafeArea& area);
    void event(const std::string& json);
    std::string probe(const std::string& code);
    void destroy();

    Renderer& renderer() { return *renderer_; }
    WidgetDiagnostics diagnostics() const;
    void onHostThread(const std::function<void()>& fn) { hostThread_.sync(fn); }

    bool waitLoaded(double timeoutSeconds = 20);
    bool waitRendered(double timeoutSeconds = 5);
    bool waitUntil(const std::function<bool(const WidgetDiagnostics&)>& pred, double timeoutSeconds = 3);

    std::function<void(const WidgetDiagnostics&)> onChange;
    std::function<void(const std::string& renderOpsJson)> onRenderOps;
    std::function<void(const WidgetAction& action)> onAction;
    std::function<void(const std::string& area, const std::map<std::string, std::string>& values)> onStorageCommit;
    std::function<void(const WidgetGestureClaim& claim)> onGesture;
    std::function<void(const WidgetRequest& request, std::function<void(const WidgetRequestOutcome&)> respond)> onRequest;
    std::function<void(const WalletCall& call, std::function<void(const WidgetRequestOutcome&)> respond)> onWallet;
    std::function<void(const std::string& text)> onCopyText;
    std::function<void(bool on)> onWatchPublicData;
    [[deprecated("use onGesture (v0.1.2)")]]
    std::function<void(bool x, bool y)> onGestureClaim;
    [[deprecated("use onAction (SDK v1)")]]
    std::function<void(const std::string& method, const std::string& argsJson)> onBridgeCall;
    std::function<void(const std::string& text)> onError;
    std::string stoppedLabel = "Widget stopped";

    void onOps(SandboxEngine&, const std::string& json) override;
    QueryAnswer onQuery(SandboxEngine&, const std::string& json) override;
    void onListen(SandboxEngine&, const std::string& json) override;
    void onPreventDefault(SandboxEngine&, const std::string& json) override;
    void onAnim(SandboxEngine&, const std::string& json) override;
    void onRequestFrame(SandboxEngine&) override;
    void onLog(SandboxEngine&, const std::string& level, const std::string& text) override;
    void onDiag(SandboxEngine&, const std::string& kind, const std::string& detail) override;
    void onBridgeAction(SandboxEngine&, const WidgetAction& action) override;
    void onResizeAnchor(SandboxEngine&, const std::string& edge) override;
    void onPublicDataWatch(SandboxEngine&, bool on) override;
    void onStorage(SandboxEngine&, const std::string& area, const StorageArea& values) override;
    void onBridgeRequest(SandboxEngine&, const WidgetRequest& request, WidgetRequestResponder respond) override;
    void onBridgeWallet(SandboxEngine&, const WalletCall& call, WidgetRequestResponder respond) override;
    void onBridgeCopyText(SandboxEngine&, const std::string& text) override;
    void onBridge(SandboxEngine&, const std::string& method, const std::string& argsJson) override;
    void onLoaded(SandboxEngine&) override;
    void onStats(SandboxEngine&, const SandboxStats& stats) override;
    void onDied(SandboxEngine&, const std::string& reason) override;

    static bool isUserActivation(const Json& ev) { return GestureCounter::isUserActivation(ev); }

private:
    GestureCounter gestures_;
    struct PendingQuery {
        int id = 0;
        bool done = false;
        std::string json = "null";
        std::optional<std::vector<uint8_t>> binary;
    };

    void onRenderer(const std::string& json);
    void handleRendererMessage(const Json& msg);
    void toRenderer(const std::string& json);
    std::string themeMessage() const;
    static std::string safeAreaMessage(const WidgetContentSafeArea& area);
    void resolveQuery(const std::string& json, std::optional<std::vector<uint8_t>> binary);
    void diag(const std::string& kind, const std::string& detail);
    void changed();
    double elapsedMs() const;
    void host(std::function<void()> fn);

    static constexpr int kRenderAckQid = -1;

    JSThread hostThread_;
    std::string html_;
    std::string theme_;
    WidgetTheme widgetTheme_;
    WidgetHostOptions options_;
    SandboxConfig config_;
    std::shared_ptr<SandboxResources> resources_;
    TrustedValidator validator_;
    std::unique_ptr<Renderer> renderer_;
    SandboxEngine engine_;

    mutable std::mutex mutex_;
    std::condition_variable cv_;
    WidgetDiagnostics diagnostics_;

    bool rendererReady_ = false;
    bool engineStarted_ = false;
    bool started_ = false;
    bool callbacksClosed_ = false;
    std::vector<std::string> pendingToRenderer_;
    double viewportW_, viewportH_, viewportDpr_;
    int qid_ = 0;
    int opsSent_ = 0;
    int tasksAtError_ = 0;

    std::atomic<bool> destroyed_{false};
    std::mutex queryMutex_;
    std::condition_variable queryCv_;
    std::shared_ptr<PendingQuery> pendingQuery_;
    const std::chrono::steady_clock::time_point createdAt_ = std::chrono::steady_clock::now();
};

}

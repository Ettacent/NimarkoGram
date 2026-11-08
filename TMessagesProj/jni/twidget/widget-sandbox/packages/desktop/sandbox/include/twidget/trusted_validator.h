/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/js_thread.h"
#include "twidget/quickjs_context.h"
#include "twidget/resources.h"

#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>

namespace twidget {

class TrustedValidator {
public:
    using DiagSink = std::function<void(const std::string& kind, const std::string& detail)>;

    TrustedValidator(const std::string& theme, std::shared_ptr<SandboxResources> resources, DiagSink onDiag);
    ~TrustedValidator();

    std::string processOps(const std::string& json);
    std::optional<std::string> validateQuery(const std::string& json);
    std::string validateListen(const std::string& json);
    std::string validatePreventDefault(const std::string& json);
    void setPolicy(const std::string& policy);
    std::string validateAnim(const std::string& json);
    std::string setTheme(const std::string& theme);
    std::string rewriteMediaQuery(const std::string& query);
    int nodeCount();
    void markLoaded();
    std::optional<std::string> stopped() const;

    using CpuSink = std::function<void(double ms)>;
    void setCpuSink(CpuSink sink);

private:
    std::string str(const char* name, const std::vector<std::string>& args);
    std::string metered(const char* name, const std::vector<std::string>& args);

    JSThread thread_;
    std::unique_ptr<QuickJSContext> js_;
    DiagSink onDiag_;
    mutable std::mutex stoppedMutex_;
    std::optional<std::string> stopped_;
    CpuSink cpuSink_;
};

}

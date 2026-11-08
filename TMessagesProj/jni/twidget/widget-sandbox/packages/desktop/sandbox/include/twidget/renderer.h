/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/resources.h"

#include <functional>
#include <memory>
#include <optional>
#include <string>

namespace twidget {

enum class WidgetPaintLimit {
    Paint,
    Backdrop,
};
constexpr int kMaxHeavyBoxes = 16;
constexpr int kMaxBackdropBoxes = 2;

inline const char* paintLimitCode(WidgetPaintLimit code) { return code == WidgetPaintLimit::Backdrop ? "backdrop-limit" : "paint-limit"; }

inline std::optional<WidgetPaintLimit> paintLimitFromCode(const std::string& code) {
    if (code == "paint-limit") return WidgetPaintLimit::Paint;
    if (code == "backdrop-limit") return WidgetPaintLimit::Backdrop;
    return std::nullopt;
}

inline std::string paintLimitMessage(WidgetPaintLimit code, int count) {
    return code == WidgetPaintLimit::Backdrop
        ? "Paint limit exceeded: " + std::to_string(count) + " boxes with backdrop-filter (max " + std::to_string(kMaxBackdropBoxes) + ")"
        : "Paint limit exceeded: " + std::to_string(count) + " boxes with shadows, filters or transparency (max " + std::to_string(kMaxHeavyBoxes) + ")";
}

class Renderer {
public:
    virtual ~Renderer() = default;

    std::function<void(const std::string& json)> onMessage;
    std::function<void(const std::string& kind, const std::string& detail)> onDiag;

    virtual void load() = 0;
    virtual void send(const std::string& json) = 0;
    virtual void setTheme(const std::string& theme) = 0;
    virtual void setWidth(double width) = 0;
    virtual void destroy() = 0;

    void limitExceeded(WidgetPaintLimit code, int count) {
        if (onMessage) onMessage(std::string("{\"type\":\"limit\",\"code\":\"") + paintLimitCode(code) + "\",\"count\":" + std::to_string(count < 0 ? 0 : count) + "}");
    }
};

using RendererFactory = std::function<std::unique_ptr<Renderer>(std::shared_ptr<SandboxResources> resources, const std::string& theme, double width)>;

}

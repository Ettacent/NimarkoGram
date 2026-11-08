/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace twidget {

struct WidgetGestureClaim {
    bool x = false;
    bool y = false;
    bool hold = false;

    constexpr bool operator==(const WidgetGestureClaim& o) const { return x == o.x && y == o.y && hold == o.hold; }
    constexpr bool operator!=(const WidgetGestureClaim& o) const { return !(*this == o); }
};

enum class PointerKind { Touch, Pen, Mouse };

enum class GestureOwner { Pending, Widget, Bubble };

enum class Gesture { Press, Tap, DoubleTap, Hold, DragX, DragY, MenuClick };

enum class BubbleAction { Menu, Swipe, Scroll, Back };

enum class GestureEvent { DblClick, ContextMenu };

struct GestureVerdict {
    GestureOwner owner = GestureOwner::Pending;
    Gesture gesture = Gesture::Press;
    bool cancel = false;
    std::optional<BubbleAction> action;
    std::optional<GestureEvent> event;
    bool edge = false;

    constexpr bool operator==(const GestureVerdict& o) const {
        return owner == o.owner && gesture == o.gesture && cancel == o.cancel && action == o.action && event == o.event && edge == o.edge;
    }
    constexpr bool operator!=(const GestureVerdict& o) const { return !(*this == o); }
};

namespace gesture {

inline constexpr double TOUCH_SLOP_PX = 10;
inline constexpr double HOLD_MS = 500;
inline constexpr double DOUBLE_TAP_MS = 300;
inline constexpr double DOUBLE_TAP_SLOP_PX = 40;
inline constexpr double EDGE_STRIP_PX = 20;

inline constexpr WidgetGestureClaim NO_CLAIM{};

WidgetGestureClaim claimOf(const std::vector<std::string>& touchActions, bool prevented = false);

std::string_view name(PointerKind pointer);
std::string_view name(GestureOwner owner);
std::string_view name(Gesture gesture);
std::string_view name(BubbleAction action);
std::string_view name(GestureEvent event);

}

class GestureArbiter {
public:
    GestureVerdict down(double t, double x, double y, const WidgetGestureClaim& claim, PointerKind pointer = PointerKind::Touch, int button = 0);
    std::optional<GestureVerdict> move(double t, double x, double y);
    std::optional<GestureVerdict> tick(double t);
    std::optional<GestureVerdict> up(double t, double x, double y);
    void cancel();
    std::optional<double> holdDeadline() const;

private:
    struct Press {
        double t0, x0, y0;
        WidgetGestureClaim claim;
        PointerKind pointer;
        bool edge;
        bool second;
        bool locked = false;
        GestureOwner owner = GestureOwner::Pending;
        Gesture gesture = Gesture::Press;
        std::optional<BubbleAction> action;
    };
    struct Tap {
        double t, x, y;
    };

    static std::optional<GestureVerdict> holdFirst(Press& p, double t);
    static GestureVerdict decide(Press& p, GestureOwner owner, Gesture gesture, std::optional<BubbleAction> action = std::nullopt,
                                 std::optional<GestureEvent> event = std::nullopt);
    static GestureVerdict verdict(const Press& p);

    std::optional<Press> press_;
    std::optional<Tap> lastTap_;
};

}

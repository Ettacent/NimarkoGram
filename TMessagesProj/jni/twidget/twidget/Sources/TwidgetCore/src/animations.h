/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "paint_policy.h"
#include "twidget/json.h"

#include <cstdint>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace twlite {

using StyleValues = std::vector<std::pair<std::string, std::string>>;
using StyleOverrides = std::map<int64_t, StyleValues>;
using Declared = std::unordered_map<std::string, std::string>;

struct Timing {
    enum Kind { Linear, Bezier, Steps, Points } kind = Linear;
    enum Jump { JumpEnd, JumpStart, JumpBoth, JumpNone } jump = JumpEnd;
    double x1 = 0, y1 = 0, x2 = 1, y2 = 1;
    int steps = 1;
    std::vector<std::pair<double, double>> points;
    std::string text = "linear";

    double css(double x) const;
    double web(double x, bool before) const;

    static std::optional<Timing> parse(std::string_view text);
    static std::optional<Timing> parse_uncached(std::string_view text);
    static Timing ease();
};

struct Keyframe {
    double offset = 0;
    StyleValues declarations;
    std::optional<Timing> timing;
};
using KeyframeRules = std::unordered_map<std::string, std::vector<Keyframe>>;
void parse_keyframes(std::string_view css, KeyframeRules& rules);

struct AnimationSpec {
    std::string name = "none";
    double duration = 0;
    std::string timing_text = "ease";
    double delay = 0;
    double iterations = 1;
    std::string direction = "normal";
    std::string fill = "none";
    bool paused = false;

    Timing timing() const;
    bool operator==(const AnimationSpec&) const = default;
    static std::vector<AnimationSpec> list(const Declared& declarations);
};

struct TransitionSpec {
    std::string property;
    double duration = 0;
    Timing timing = Timing::ease();
    double delay = 0;
    static std::vector<TransitionSpec> list(const Declared& declarations);
};

std::string interpolate(const std::string& a, const std::string& b, double t);

struct WebAnimation {
    struct Frame {
        double offset = 0;
        Timing easing;
        std::string composite;
        StyleValues values;
    };
    int64_t aid = 0;
    int64_t target = 0;
    std::vector<Frame> frames;
    double duration = 0, delay = 0, end_delay = 0, iterations = 1, iteration_start = 0;
    std::string direction = "normal", fill = "none";
    Timing easing;
    std::string composite = "replace", iteration_composite = "replace";
    std::optional<double> start_ms, hold_ms;
    double rate = 1;
    std::vector<std::string> properties;
    std::optional<paint::BoxPaint> heavy;

    bool running() const { return !hold_ms && start_ms && rate != 0; }
    static std::optional<WebAnimation> parse(const twidget::Json& record, double clock_offset_ms);
};

struct AnimationEvent {
    std::string type;
    int64_t target = 0;
    std::string name;
    double elapsed = 0;
};

class Animator {
public:
    static const std::vector<std::string>& watched();
    static const std::vector<std::string>& entry_properties();

    void set_style_sheets(const std::vector<std::string>& texts);
    void update(const std::unordered_map<int64_t, Declared>& declarations, double now);
    StyleOverrides sample(double now);
    bool active(double now) const;
    bool active(double now, const std::unordered_set<int64_t>& skip) const;
    std::vector<AnimationEvent> take_events() { return std::move(events_); }


    std::vector<int64_t> animated_ids(double now) const;
    std::vector<std::string> animated_properties(int64_t id) const;
    StyleValues peek(int64_t id, double t) const;
    std::string base_value(int64_t id, const std::string& property) const { return underlying_value(id, property); }
    bool declares_motion(int64_t id) const;
    struct Plan {
        bool ready = true;
        double start = 0, duration = 0;
        bool repeat = false;
        double wake = 0;
    };
    std::optional<Plan> plan(int64_t id, double now, bool iterations) const;
    uint64_t signature(int64_t id) const;

    void apply_web(const twidget::Json& message, double clock_offset_ms);
    std::vector<int64_t> web_targets() const;
    std::vector<std::string> web_properties(int64_t id) const;
    twidget::Json css_animations(const std::vector<int64_t>& ids, double now) const;
    void prune(const std::function<bool(int64_t)>& alive);

    struct Motion {
        int64_t id = 0;
        char kind = 'a';
        std::string_view name;
        int64_t aid = 0;
        paint::BoxPaint paint;
    };
    uint64_t heavy_motion(double now, std::vector<Motion>* out) const;

    std::unordered_map<int64_t, Declared> underlying;

private:
    struct Running {
        AnimationSpec spec;
        double start = 0;
        int iteration = 0;
        bool ended = false;
        std::optional<double> paused_at;
        double paused_duration = 0;
        double timeline_start() const { return start + paused_duration; }
        double local_time(double now) const { return paused_at.value_or(now) - timeline_start(); }
    };
    struct Transition {
        std::string from, to;
        double start = 0;
        TransitionSpec spec;
        std::optional<paint::BoxPaint> heavy;
    };
    struct Override {
        double origin = 0;
        std::optional<double> start, hold;
        double rate = 1;
        bool cancelled = false;
        double local(double now) const { return hold ? *hold : (now - (start ? *start : now)) * rate; }
    };

    std::string underlying_value(int64_t id, const std::string& property) const;
    std::optional<StyleValues> sample_animation(int64_t id, Running& item, const std::vector<Keyframe>& frames, double now, std::vector<AnimationEvent>* events) const;
    StyleValues values(const std::vector<Keyframe>& frames, const Declared& base, double progress, const AnimationSpec& spec) const;
    std::optional<std::string> transition_value(int64_t id, const std::string& property, double now) const;
    void prune_overrides();
    void sample_web(StyleOverrides& out, double now, int64_t only = 0) const;
    const Override* override_of(int64_t id, const std::string& key) const;

    KeyframeRules keyframes_;
    mutable std::unordered_map<std::string, std::optional<paint::BoxPaint>> keyframes_paint_;
    std::unordered_map<int64_t, std::vector<Running>> running_;
    std::unordered_map<int64_t, std::map<std::string, Transition>> transitions_;
    std::unordered_map<int64_t, Declared> bases_;
    std::unordered_map<int64_t, std::map<std::string, Override>> overrides_;
    std::map<int64_t, WebAnimation> web_;
    std::vector<AnimationEvent> events_;
};

}

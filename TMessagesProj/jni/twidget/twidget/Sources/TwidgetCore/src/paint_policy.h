/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/renderer.h"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace twlite::paint {

struct BoxPaint {
    bool heavy = false;
    bool backdrop = false;
    bool operator==(const BoxPaint&) const = default;
};

struct Style {
    std::string_view display, content, box_shadow, filter, backdrop_filter, opacity;
    enum class Button { None, Disabled, Pressed } button = Button::None;
};

bool box_shadow_blurred(std::string_view value);
std::string box_shadow_without_blur(std::string_view value);
bool hides_subtree(std::string_view display);
std::optional<BoxPaint> box_paint(const Style& style, bool pseudo);

class AnimationPaint {
public:
    void keyframe(std::optional<double> offset) { at_ = offset; }
    void property(std::string_view name, std::optional<std::string_view> value);
    std::optional<BoxPaint> result() const;

private:
    std::optional<double> at_;
    bool heavy_ = false, backdrop_ = false, shadow_ = false, shadow_blurred_ = false, shadow_first_ = false, shadow_last_ = false;
};

struct Verdict {
    twidget::WidgetPaintLimit code = twidget::WidgetPaintLimit::Paint;
    int count = 0;
    bool operator==(const Verdict&) const = default;
};

template <class Key, class Hash = std::hash<Key>>
class PaintCounter {
public:
    int heavy() const { return heavy_; }
    int backdrop() const { return backdrop_; }
    int peak_heavy() const { return peak_heavy_; }
    int peak_backdrop() const { return peak_backdrop_; }

    void set_box(const Key& key, std::optional<BoxPaint> paint) {
        auto it = boxes_.find(key);
        if (it != boxes_.end()) it->second.seen = scan_;
        if (!paint && it == boxes_.end()) return;
        update(key, [&](Box& b) { b.paint = paint; });
    }
    void remove_box(const Key& key) {
        if (boxes_.count(key)) update(key, [](Box& b) {
            b.paint.reset();
            b.anims.clear();
        });
    }
    void start_animation(const Key& key, std::string_view id, BoxPaint paint) {
        update(key, [&](Box& b) {
            for (Anim& a : b.anims) {
                if (a.id != id) continue;
                a.n++;
                a.backdrop = a.backdrop || paint.backdrop;
                return;
            }
            b.anims.push_back({std::string(id), 1, paint.backdrop});
        });
    }
    void end_animation(const Key& key, std::string_view id) {
        if (!animating(key, id)) return;
        update(key, [&](Box& b) {
            for (auto a = b.anims.begin(); a != b.anims.end(); ++a) {
                if (a->id != id) continue;
                if (--a->n <= 0) b.anims.erase(a);
                return;
            }
        });
    }
    bool has_box(const Key& key) const {
        auto it = boxes_.find(key);
        return it != boxes_.end() && it->second.paint.has_value();
    }
    bool animating(const Key& key, std::string_view id) const {
        auto it = boxes_.find(key);
        if (it == boxes_.end()) return false;
        for (const Anim& a : it->second.anims)
            if (a.id == id) return true;
        return false;
    }
    std::optional<Verdict> check() {
        if (stopped_) return stopped_;
        if (heavy_ > twidget::kMaxHeavyBoxes) stopped_ = Verdict{twidget::WidgetPaintLimit::Paint, heavy_};
        else if (backdrop_ > twidget::kMaxBackdropBoxes) stopped_ = Verdict{twidget::WidgetPaintLimit::Backdrop, backdrop_};
        return stopped_;
    }

    void begin_scan() { scan_++; }
    bool end_scan() {
        bool anims = false;
        for (auto it = boxes_.begin(); it != boxes_.end();) {
            if (it->second.seen == scan_) {
                ++it;
                continue;
            }
            auto [heavy, backdrop] = contribution(it->second);
            heavy_ -= heavy;
            backdrop_ -= backdrop;
            anims = anims || !it->second.anims.empty();
            it = boxes_.erase(it);
        }
        return anims;
    }

private:
    struct Anim {
        std::string id;
        int n = 0;
        bool backdrop = false;
    };
    struct Box {
        std::optional<BoxPaint> paint;
        std::vector<Anim> anims;
        uint32_t seen = 0;
    };

    static BoxPaint contribution(const Box& b) {
        if (!b.paint) return {};
        BoxPaint out = *b.paint;
        for (const Anim& a : b.anims) {
            out.heavy = true;
            out.backdrop = out.backdrop || a.backdrop;
        }
        return out;
    }
    template <class Change> void update(const Key& key, Change change) {
        auto it = boxes_.find(key);
        BoxPaint before = it == boxes_.end() ? BoxPaint{} : contribution(it->second);
        if (it == boxes_.end()) {
            it = boxes_.emplace(key, Box{}).first;
            it->second.seen = scan_;
        }
        change(it->second);
        BoxPaint after = contribution(it->second);
        heavy_ += int(after.heavy) - int(before.heavy);
        backdrop_ += int(after.backdrop) - int(before.backdrop);
        if (!it->second.paint && it->second.anims.empty()) boxes_.erase(it);
        if (heavy_ > peak_heavy_) peak_heavy_ = heavy_;
        if (backdrop_ > peak_backdrop_) peak_backdrop_ = backdrop_;
    }

    std::unordered_map<Key, Box, Hash> boxes_;
    int heavy_ = 0, backdrop_ = 0, peak_heavy_ = 0, peak_backdrop_ = 0;
    uint32_t scan_ = 0;
    std::optional<Verdict> stopped_;
};

}

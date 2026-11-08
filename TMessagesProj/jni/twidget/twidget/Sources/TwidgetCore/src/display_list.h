/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace twlite {

struct Color {
    uint8_t r = 0, g = 0, b = 0, a = 255;
};

struct Rect {
    float x = 0, y = 0, w = 0, h = 0;
    bool empty() const { return w <= 0 || h <= 0; }
};

struct Affine {
    float a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
    Affine operator*(const Affine& next) const {
        return Affine{a * next.a + c * next.b, b * next.a + d * next.b, a * next.c + c * next.d,
                      b * next.c + d * next.d, a * next.e + c * next.f + e, b * next.e + d * next.f + f};
    }
    static Affine translate(float x, float y) { return Affine{1, 0, 0, 1, x, y}; }
    float apply_x(float x, float y) const { return a * x + c * y + e; }
    float apply_y(float x, float y) const { return b * x + d * y + f; }
    bool invert(Affine& out) const {
        float det = a * d - b * c;
        if (!(det != 0) || !std::isfinite(det)) return false;
        out = Affine{d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det};
        return true;
    }
    bool operator==(const Affine&) const = default;
};

struct Radii {
    float tlx = 0, tly = 0, trx = 0, try_ = 0, brx = 0, bry = 0, blx = 0, bly = 0;
    bool any() const { return tlx > 0 || tly > 0 || trx > 0 || try_ > 0 || brx > 0 || bry > 0 || blx > 0 || bly > 0; }
};

struct Stop {
    float offset = 0;
    Color color;
};

struct Filter {
    enum Kind : uint8_t { Blur, Brightness, Contrast, Grayscale, HueRotate, Invert, Opacity, Saturate, Sepia, DropShadow } kind = Blur;
    float amount = 0;
    float x = 0, y = 0;
    Color color;
};
using Filters = std::vector<Filter>;

enum class Cmd {
    FillRect,
    StrokeRect,
    FillEllipse,
    StrokeEllipse,
    Text,
    Image,
    Linear,
    Radial,
    Conic,
    ClipPush,
    ClipPop,
    Path,
    GroupPush,
    GroupPop,
    Shadow,
    FilterPush,
    FilterPop,
    Backdrop,
};

struct Path {
    enum Op : uint8_t { Move, Line, Quad, Cubic, Close };
    std::vector<Op> ops;
    std::vector<float> points;
    bool fill = false;
    bool evenodd = false;
    Color fill_color;
    int gradient = 0;
    float x1 = 0, y1 = 0, x2 = 0, y2 = 0, r = 0, fx = 0, fy = 0;
    std::vector<Stop> stops;
    bool stroke = false;
    Color stroke_color;
    float stroke_width = 1;
    int line_cap = 0;
    int line_join = 0;
    float miter_limit = 4;
    std::vector<float> dashes;
    float dash_offset = 0;
    int stroke_gradient = 0;
    float sg[7] = {};
    std::vector<Stop> stroke_stops;
};

struct Command {
    Cmd kind = Cmd::FillRect;
    Rect rect;
    Radii radii;
    Rect clip;
    bool has_clip = false;
    Color color;
    float width = 0;
    int font = 0;
    std::string text;
    float x = 0, y = 0;
    int image = 0;
    int canvas = 0;
    float ax = 0, ay = 0, bx = 0, by = 0;
    std::vector<Stop> stops;
    std::shared_ptr<const Path> path;
    std::shared_ptr<const Filters> filter;
    float opacity = 1;
    float matrix[6] = {1, 0, 0, 1, 0, 0};
    bool hidden = false;
    bool inset = false;
    uint64_t key = 0;
};

using DisplayList = std::vector<Command>;

std::string to_json(const DisplayList& list);

std::string base64(const std::vector<uint8_t>& bytes);

}

/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "display_list.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace twlite {

struct Geometry {
    std::vector<Path::Op> ops;
    std::vector<float> points;
    bool empty() const { return ops.empty(); }
};

struct CanvasPaint {
    enum Kind : uint8_t { Solid, Linear, Radial, Conic } kind = Solid;
    Color color;
    float args[6] = {};
    std::vector<Stop> stops;
};

enum class Composite : uint8_t {
    SourceOver, SourceIn, SourceOut, SourceAtop, DestinationOver, DestinationIn, DestinationOut, DestinationAtop,
    Lighter, Copy, Xor, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn, HardLight, SoftLight,
    Difference, Exclusion, Hue, Saturation, Color, Luminosity,
};

struct CanvasClip {
    Geometry path;
    bool evenodd = false;
};

struct CanvasOp {
    enum Kind : uint8_t {
        Reset,
        Fill,
        Stroke,
        Clear,
        Text,
        Image,
        PutImage,
    } kind = Fill;

    Geometry path;
    bool evenodd = false;
    CanvasPaint paint;
    float matrix[6] = {1, 0, 0, 1, 0, 0};

    float line_width = 1;
    int line_cap = 0;
    int line_join = 0;
    float miter_limit = 10;
    std::vector<float> dashes;
    float dash_offset = 0;

    float alpha = 1;
    Composite composite = Composite::SourceOver;
    Color shadow_color{0, 0, 0, 0};
    float shadow_blur = 0, shadow_x = 0, shadow_y = 0;
    std::shared_ptr<const std::vector<CanvasClip>> clips;

    int font = 0;
    std::string text;
    float x = 0, y = 0, scale_x = 1;
    float text_dx = 0;
    bool stroke_text = false;

    int image = 0;
    int canvas = 0;
    float sx = 0, sy = 0, sw = -1, sh = -1;
    float dx = 0, dy = 0, dw = 0, dh = 0;
    bool smoothing = true;

    std::shared_ptr<const std::vector<uint8_t>> rgba;
    int w = 0, h = 0;

    bool has_shadow() const { return shadow_color.a > 0 && (shadow_blur > 0 || shadow_x != 0 || shadow_y != 0); }
};

std::string to_json(const std::vector<CanvasOp>& ops);

}

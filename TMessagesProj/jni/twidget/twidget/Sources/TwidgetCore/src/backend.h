/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "canvas_ops.h"

#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace twlite {

struct FontMetrics {
    float ascent = 0;
    float descent = 0;
    float height = 0;
    float x_height = 0;
    float zero_width = 0;
    float em_ascent = 0, em_descent = 0;
};

inline bool set_em_box(FontMetrics& m, float ascent, float descent, float size) {
    float height = ascent + descent;
    if (ascent < 0 || descent < 0 || height <= 0 || size <= 0) return false;
    m.em_ascent = std::round(ascent * size / height * 64) / 64;
    m.em_descent = size - m.em_ascent;
    return true;
}

class Backend {
public:
    virtual ~Backend() = default;
    virtual int create_font(const std::string& families, float size_px, int weight, bool italic, const std::string& features, FontMetrics& out) = 0;
    virtual void delete_font(int font) = 0;
    virtual float text_width(int font, const std::string& text) = 0;
    virtual bool load_image(int id, const std::string& url, float& w, float& h) = 0;

    virtual void canvas_run(int  , int  , int  , const std::vector<CanvasOp>&  ) {}
    virtual bool canvas_read(int  , int  , int  , int  , int  , std::vector<uint8_t>&  ) { return false; }
    virtual void canvas_release(int  ) {}
};

}

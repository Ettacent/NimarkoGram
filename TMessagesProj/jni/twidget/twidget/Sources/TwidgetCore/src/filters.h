/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "display_list.h"

#include <cstddef>
#include <cstdint>

namespace twlite {

struct ColorMatrix {
    float rgb[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    float offset = 0;
    float alpha = 1;
};
bool color_matrix(const Filter& f, ColorMatrix& out);

struct FilterReach {
    float left = 0, top = 0, right = 0, bottom = 0;
};
FilterReach filter_reach(const Filters& filters);

void filter_pixels(uint8_t* pixels, int width, int height, size_t stride, bool bgra, const Filters& filters, float scale);

}

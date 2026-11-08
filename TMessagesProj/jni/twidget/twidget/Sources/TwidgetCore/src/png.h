/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include <cstddef>
#include <cstdint>

namespace twlite {

size_t png_intact_rows(const uint8_t* data, size_t size);

bool image_dimensions(const uint8_t* data, size_t size, uint32_t& w, uint32_t& h);

}

/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "twidget/json.h"

#include <litehtml.h>

#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace twlite {

struct ComputedExtras {
    const std::vector<std::pair<std::string, std::string>>* animated = nullptr;
};

twidget::Json computed_style(const litehtml::element::ptr& el, std::string_view pseudo, const ComputedExtras& extras);

std::string computed_custom_property(const litehtml::element::ptr& el, const std::string& name);

std::string computed_touch_action(const litehtml::element::ptr& el);

std::string computed_pointer_events(const litehtml::element::ptr& el);

}

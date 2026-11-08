/* Modifications Copyright (C) 2026 Ettacent */

#pragma once

#include "display_list.h"
#include "twidget/json.h"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace twlite::css {

std::string trim(std::string_view s);
std::string lower(std::string s);
bool iequals(std::string_view a, std::string_view b);
bool starts_with(std::string_view s, std::string_view prefix);
bool ends_with(std::string_view s, std::string_view suffix);
std::optional<double> number(std::string_view s);

std::vector<std::string> split_top_level(std::string_view text, char separator);

struct Declaration {
    std::string name, value;
    bool important = false;
};
std::vector<Declaration> parse_declarations(std::string_view text);

bool is_tag_name(std::string_view name);
bool is_attribute_name(std::string_view name);
bool is_property_name(std::string_view name);

std::string resolve_light_dark(std::string_view css, bool dark);

bool ident_char(char c);
size_t skip_opaque(std::string_view css, size_t i);

std::optional<double> parse_time(std::string_view text);

bool parse_color(std::string_view text, Color& out);
std::string normalize_colors(std::string_view text);

std::string format_number(double value, int digits = 4);

struct Font {
    std::string family = "sans-serif";
    float size = 10;
    int weight = 400;
    bool italic = false;
};
Font parse_font(std::string_view font);

std::optional<Affine> parse_transform(std::string_view text, float w, float h);
std::optional<Affine> parse_translate(std::string_view text, float w, float h);
std::optional<Affine> parse_rotate(std::string_view text);
std::optional<Affine> parse_scale(std::string_view text);
std::optional<std::pair<float, float>> parse_origin(std::string_view text, float w, float h);
std::optional<float> parse_opacity(std::string_view text);

std::vector<std::pair<std::string, std::string>> theme_variables(const twidget::Json& theme);
std::vector<std::pair<std::string, std::string>> content_safe_area_variables(const twidget::Json& inset);
bool is_theme_variable(std::string_view name);

std::string substitute_variables(std::string_view css, const std::vector<std::pair<std::string, std::string>>& vars);
std::string unwrap_where(std::string_view css);

}

/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_TYPES_H
#define LITEHTML_TYPES_H

#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include <map>
#include <list>
#include <variant>
#include <algorithm>
#include "css_values.h"
#include "pixel_type.h"

namespace litehtml
{
    using uint_ptr = uintptr_t;

    class document;
    class element;

    using string_map    = std::map<std::string, std::string>;
    using elements_list = std::list<std::shared_ptr<element>>;
    using int_vector    = std::vector<int>;
    using string_vector = std::vector<std::string>;
    using pixel_vector  = std::vector<pixel_t>;

    template <class... Types> struct variant : std::variant<Types...>
    {
        using base = variant<Types...>;
        using std::variant<Types...>::variant;
        template <class T> bool is() const
        {
            return std::holds_alternative<T>(*this);
        }
        template <class T> const T& get() const
        {
            return std::get<T>(*this);
        }
        template <class T> T& get()
        {
            return std::get<T>(*this);
        }
    };

    enum document_mode
    {
        no_quirks_mode,
        quirks_mode,
        limited_quirks_mode
    };

    struct rendered_width
    {
        pixel_t natural_width = 0_px;
        pixel_t min_width     = 0_px;

        void merge(const rendered_width& other)
        {
            natural_width = std::max(natural_width, other.natural_width);
            min_width     = std::max(min_width, other.min_width);
        }

        void reset()
        {
            natural_width = 0_px;
            min_width     = 0_px;
        }
    };

    using byte    = unsigned char;
    using ucode_t = unsigned int;

    struct margins
    {
        pixel_t left;
        pixel_t right;
        pixel_t top;
        pixel_t bottom;

        pixel_t width() const
        {
            return left + right;
        }
        pixel_t height() const
        {
            return top + bottom;
        }
    };

    struct pointF
    {
        float x;
        float y;

        pointF() :
            x(0),
            y(0)
        {
        }
        pointF(float _x, float _y) :
            x(_x),
            y(_y)
        {
        }

        void set(float _x, float _y)
        {
            x = _x;
            y = _y;
        }
    };

    struct size
    {
        pixel_t width;
        pixel_t height;

        size(pixel_t w, pixel_t h) :
            width(w),
            height(h)
        {
        }

        size() :
            width(0),
            height(0)
        {
        }
    };

    inline float& pixel_snap_scale()
    {
        thread_local float scale = 1;
        return scale;
    }

    struct position
    {
        using vector = std::vector<position>;

        pixel_t x;
        pixel_t y;
        pixel_t width;
        pixel_t height;

        position() = default;

        position(pixel_t _x, pixel_t _y, pixel_t _width, pixel_t _height) :
            x(_x),
            y(_y),
            width(_width),
            height(_height)
        {
        }

        pixel_t right() const
        {
            return x + width;
        }
        pixel_t bottom() const
        {
            return y + height;
        }
        pixel_t left() const
        {
            return x;
        }
        pixel_t top() const
        {
            return y;
        }

        void operator+=(const margins& mg)
        {
            x      -= mg.left;
            y      -= mg.top;
            width  += mg.left + mg.right;
            height += mg.top + mg.bottom;
        }
        void operator-=(const margins& mg)
        {
            x      += mg.left;
            y      += mg.top;
            width  -= mg.left + mg.right;
            height -= mg.top + mg.bottom;
        }

        void clear()
        {
            x = y = width = height = 0_px;
        }

        void round()
        {
            const float k      = pixel_snap_scale();
            const float left   = std::round(x.value() * k) / k;
            const float top    = std::round(y.value() * k) / k;
            const float right  = std::round((x.value() + width.value()) * k) / k;
            const float bottom = std::round((y.value() + height.value()) * k) / k;
            x                  = left;
            y                  = top;
            width              = right - left;
            height             = bottom - top;
        }

        position& operator=(const size& sz)
        {
            width  = sz.width;
            height = sz.height;
            return *this;
        }

        bool operator==(const position& val) const
        {
            return x == val.x && y == val.y && width == val.width && height == val.height;
        }

        void move_to(pixel_t _x, pixel_t _y)
        {
            x = _x;
            y = _y;
        }

        [[nodiscard]]
        bool does_intersect(const position* val, bool can_touch = false) const
        {
            if(!val)
            {
                return true;
            }

            if(!can_touch)
            {
                return (left() <= val->right() && right() >= val->left() && bottom() >= val->top() &&
                        top() <= val->bottom()) ||
                       (val->left() <= right() && val->right() >= left() && val->bottom() >= top() &&
                        val->top() <= bottom());
            }
            return (left() < val->right() && right() > val->left() && bottom() > val->top() && top() < val->bottom()) ||
                   (val->left() < right() && val->right() > left() && val->bottom() > top() && val->top() < bottom());
        }

        [[nodiscard]]
        bool on_same_line(const position& val, bool can_touch = false) const
        {
            if(can_touch)
            {
                return !(bottom() <= val.top() || top() >= val.bottom());
            }
            return !(bottom() < val.top() || top() > val.bottom());
        }

        [[nodiscard]]
        position intersect(const position& src) const
        {
            position dest;
            pixel_t  dest_x  = std::max(src.x, x);
            pixel_t  dest_y  = std::max(src.y, y);
            pixel_t  dest_x2 = std::min(src.right(), right());
            pixel_t  dest_y2 = std::min(src.bottom(), bottom());

            if(dest_x2 > dest_x && dest_y2 > dest_y)
            {
                dest.x      = dest_x;
                dest.y      = dest_y;
                dest.width  = dest_x2 - dest_x;
                dest.height = dest_y2 - dest_y;
            } else
            {
                dest.width  = 0;
                dest.height = 0;
            }

            return dest;
        }

        [[nodiscard]]
        bool empty() const
        {
            return width == 0_px && height == 0_px;
        }

        [[nodiscard]]
        bool is_point_inside(pixel_t _x, pixel_t _y) const
        {
            return (_x >= left() && _x < right() && _y >= top() && _y < bottom());
        }
    };

    struct scroll_values
    {
        pixel_t  dx;
        pixel_t  dy;
        position scroll_box;

        scroll_values()
        {
            dx = 0_px;
            dy = 0_px;
        }
    };

    struct font_metrics
    {
        pixel_t font_size;
        pixel_t height;
        pixel_t ascent;
        pixel_t descent;
        pixel_t x_height;
        pixel_t ch_width;
        bool draw_spaces = true;
        pixel_t sub_shift;
        pixel_t super_shift;

        pixel_t base_line() const
        {
            return descent;
        }
    };

    struct font_item
    {
        uint_ptr     font = 0;
        font_metrics metrics;
    };

    using fonts_map = std::map<std::string, font_item>;

    enum draw_flag
    {
        draw_root,
        draw_block,
        draw_floats,
        draw_inlines,
        draw_positioned,
    };

    enum contain_flags
    {
        contain_size        = 0x01,
        contain_inline_size = 0x02,
        contain_layout      = 0x04,
        contain_style       = 0x08,
        contain_paint       = 0x10,
    };

    struct containing_block_context
    {
        enum cbc_value_type
        {
            cbc_value_type_absolute,
            cbc_value_type_percentage,
            cbc_value_type_auto,
            cbc_value_type_none,
        };

        enum cbc_size_mode
        {
            size_mode_normal       = 0x00,
            size_mode_exact_width  = 0x01,
            size_mode_exact_height = 0x02,
            size_mode_content      = 0x04,
            size_mode_resolved_width = 0x08,
        };

        struct typed_pixel
        {
            pixel_t        value;
            cbc_value_type type;

            typed_pixel(const typed_pixel& v) = default;

            typed_pixel(pixel_t val, cbc_value_type tp) :
                value(val),
                type(tp)
            {
            }

            operator pixel_t() const
            {
                return value;
            }

            typed_pixel& operator=(pixel_t val)
            {
                value = val;
                return *this;
            }

            typed_pixel& operator=(const typed_pixel& v) = default;
        };

        typed_pixel width        = {0_px, cbc_value_type_auto};
        typed_pixel render_width = {0_px, cbc_value_type_auto};
        typed_pixel min_width    = {0_px, cbc_value_type_none};
        typed_pixel max_width    = {0_px, cbc_value_type_none};

        typed_pixel height     = {0_px, cbc_value_type_auto};
        typed_pixel min_height = {0_px, cbc_value_type_none};
        typed_pixel max_height = {0_px, cbc_value_type_none};

        int      context_idx = 0;
        uint32_t size_mode   = size_mode_normal;

        containing_block_context new_width(pixel_t w, uint32_t _size_mode = size_mode_normal) const
        {
            containing_block_context ret = *this;
            ret.render_width             = w - (static_cast<pixel_t>(ret.width) - ret.render_width);
            ret.width                    = w;
            ret.size_mode                = _size_mode;
            return ret;
        }

        containing_block_context new_width_height(pixel_t w, pixel_t h, uint32_t _size_mode = size_mode_normal) const
        {
            containing_block_context ret = *this;
            ret.render_width             = w - (static_cast<pixel_t>(ret.width) - ret.render_width);
            ret.width                    = w;
            ret.height                   = h;
            ret.size_mode                = _size_mode;
            return ret;
        }
    };

    class render_item;

    struct floated_box
    {
        position                     pos;
        element_float                float_side   = float_none;
        element_clear                clear_floats = clear_none;
        int                          context      = 0;
        pixel_t                      min_width;
        std::shared_ptr<render_item> el;
    };

    struct pixel_pixel_cache
    {
        pixel_t hash;
        pixel_t val;
        bool    is_valid;
        bool    is_default;

        pixel_pixel_cache()
        {
            hash       = 0;
            val        = 0;
            is_valid   = false;
            is_default = false;
        }
        void invalidate()
        {
            is_valid   = false;
            is_default = false;
        }
        void set_value(pixel_t vHash, pixel_t vVal)
        {
            hash     = vHash;
            val      = vVal;
            is_valid = true;
        }
    };

    enum select_result
    {
        select_no_match           = 0x00,
        select_match              = 0x01,
        select_match_pseudo_class = 0x02,
        select_match_with_before  = 0x10,
        select_match_with_after   = 0x20,
    };

    template <class T> class def_value
    {
        T    m_val;
        bool m_is_default;

      public:
        def_value(T def_val)
        {
            m_is_default = true;
            m_val        = def_val;
        }
        def_value(const def_value<T>& val)
        {
            m_is_default = val.m_is_default;
            m_val        = val.m_val;
        }
        const T& value() const
        {
            return m_val;
        }
        void reset(T def_val)
        {
            m_is_default = true;
            m_val        = def_val;
        }
        bool is_default() const
        {
            return m_is_default;
        }
        T operator=(T new_val)
        {
            m_val        = new_val;
            m_is_default = false;
            return m_val;
        }
        def_value<T>& operator=(const def_value<T>& val) = default;

        operator T() const
        {
            return m_val;
        }
    };

    class baseline
    {
      public:
        enum _baseline_type
        {
            baseline_type_none,
            baseline_type_top,
            baseline_type_bottom,
        };

      public:
        baseline() :
            m_value(0),
            m_type(baseline_type_none)
        {
        }
        baseline(pixel_t _value, _baseline_type _type) :
            m_value(_value),
            m_type(_type)
        {
        }

        pixel_t value() const
        {
            return m_value;
        }
        void value(pixel_t _value)
        {
            m_value = _value;
        }
        _baseline_type type() const
        {
            return m_type;
        }
        void type(_baseline_type _type)
        {
            m_type = _type;
        }

        operator pixel_t() const
        {
            return m_value;
        }
        baseline& operator=(pixel_t _value)
        {
            m_value = _value;
            return *this;
        }

        void set(pixel_t _value, _baseline_type _type)
        {
            m_value = _value;
            m_type  = _type;
        }
        pixel_t get_offset_from_top(pixel_t height) const
        {
            if(m_type == baseline_type_top)
            {
                return m_value;
            }
            return height - m_value;
        }
        pixel_t get_offset_from_bottom(pixel_t height) const
        {
            if(m_type == baseline_type_bottom)
            {
                return m_value;
            }
            return height - m_value;
        }
        void calc(pixel_t top, pixel_t bottom)
        {
            if(m_type == baseline_type_top)
            {
                m_value = -top;
            } else if(m_type == baseline_type_bottom)
            {
                m_value = bottom;
            }
        }

      private:
        pixel_t        m_value;
        _baseline_type m_type;
    };

    struct media_features
    {
        media_type type;
        pixel_t    width;
        pixel_t height;
        pixel_t
            device_width;
        pixel_t device_height;
        int color;
        int color_index;
        int monochrome;
        pixel_t resolution;

        media_features()
        {
            type          = media_type_unknown;
            width         = 0;
            height        = 0;
            device_width  = 0;
            device_height = 0;
            color         = 0;
            color_index   = 0;
            monochrome    = 0;
            resolution    = 0;
        }
    };

    enum render_type
    {
        render_all,
        render_no_fixed,
        render_fixed_only,
    };

    constexpr auto split_delims_spaces = " \t\r\n\f\v";

}

#endif

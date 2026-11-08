/* Modifications Copyright (C) 2026 Ettacent */

#include "html_microsyntaxes.h"
#include "html.h"

namespace litehtml
{

    bool html_parse_integer(const std::string& str, int& val)
    {
        const char* ptr = str.c_str();
        char*       end;
        int n = strtol(ptr, &end, 10);
        if(end == ptr)
        {
            return false;
        }
        val = n;
        return true;
    }

    bool html_parse_non_negative_integer(const std::string& str, int& val)
    {
        int n = 0;
        if(!html_parse_integer(str, n) || n < 0)
        {
            return false;
        }
        val = n;
        return true;
    }

    bool html_parse_dimension_value(const std::string& str, float& result, html_dimension_type& type)
    {
        auto position = str.c_str();
        while(is_whitespace(*position))
        {
            position++;
        }
        if(!is_digit(*position))
        {
            return false;
        }
        char* end;
        auto  value = static_cast<float>(strtol(position, &end, 10));
        position    = end;
        if(!*position)
        {
            result = value;
            type   = html_length;
            return true;
        }
        if(*position == '.')
        {
            position++;
            if(!is_digit(*position))
            {
                result = value;
                type   = *position == '%' ? html_percentage : html_length;
                return true;
            }
            float divisor = 1;
            while(true)
            {
                divisor *= 10;
                value += digit_value(*position) / divisor;
                position++;
                if(!*position)
                {
                    result = value;
                    type   = html_length;
                    ;
                    return true;
                }
                if(!is_digit(*position))
                {
                    break;
                }
            }
        }
        result = value;
        type   = *position == '%' ? html_percentage : html_length;
        return true;
    }

    bool html_parse_nonzero_dimension_value(const std::string& str, float& val, html_dimension_type& type)
    {
        float               x;
        html_dimension_type t;
        if(!html_parse_dimension_value(str, x, t) || pixel_t(x) == 0_px)
        {
            return false;
        }
        val  = x;
        type = t;
        return true;
    }

}

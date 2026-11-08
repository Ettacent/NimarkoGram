/* Modifications Copyright (C) 2026 Ettacent */

#include "utf8_strings.h"
#include "types.h"

namespace litehtml
{

    char32_t read_utf8_char(const std::string& str, int& index)
    {
        auto getb = [&]() -> byte {
            if(!str[index])
            {
                return 0;
            }
            return str[index++];
        };

        byte b1 = getb();

        if((b1 & 0x80) == 0)
        {
            return b1;
        }
        if((b1 & 0xe0) == 0xc0)
        {
            char32_t r  = (b1 & 0x1f) << 6;
            r          |= getb() & 0x3f;
            return r;
        }
        if((b1 & 0xf0) == 0xe0)
        {
            char32_t r  = (b1 & 0x0f) << 12;
            r          |= (getb() & 0x3f) << 6;
            r          |= getb() & 0x3f;
            return r;
        }
        if((b1 & 0xf8) == 0xf0)
        {
            byte b2 = getb() & 0x3f;
            byte b3 = getb() & 0x3f;
            byte b4 = getb() & 0x3f;
            return ((b1 & 7) << 18) | (b2 << 12) | (b3 << 6) | b4;
        }

        return 0xFFFD;
    }

    void prev_utf8_char(const std::string& str, int& index)
    {
        while(index && (static_cast<byte>(str[--index]) >> 6) == 0b10)
            ;
    }

    void append_char(std::string& str, char32_t code)
    {
        if(code <= 0x7F)
        {
            str += static_cast<char>(code);
        } else if(code <= 0x7FF)
        {
            str += static_cast<char>((code >> 6) + 192);
            str += (code & 63) + 128;
        } else if(0xd800 <= code && code <= 0xdfff)
        {
        } else if(code <= 0xFFFF)
        {
            str += static_cast<char>((code >> 12) + 224);
            str += ((code >> 6) & 63) + 128;
            str += (code & 63) + 128;
        } else if(code <= 0x10FFFF)
        {
            str += static_cast<char>((code >> 18) + 240);
            str += ((code >> 12) & 63) + 128;
            str += ((code >> 6) & 63) + 128;
            str += (code & 63) + 128;
        }
    }

    utf8_to_utf32::utf8_to_utf32(const std::string& val)
    {
        int index = 0;
        while(char32_t ch = read_utf8_char(val, index))
        {
            m_str += ch;
        }
    }

    utf32_to_utf8::utf32_to_utf8(const std::u32string& val)
    {
        for(auto ch : val)
        {
            append_char(m_str, ch);
        }
    }

}

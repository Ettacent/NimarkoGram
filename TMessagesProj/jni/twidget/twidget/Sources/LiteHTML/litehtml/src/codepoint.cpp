/* Modifications Copyright (C) 2026 Ettacent */

// Copyright (C) 2020-2021 Primate Labs Inc.
//    * Redistributions of source code must retain the above copyright
// copyright notice, this list of conditions and the following disclaimer
//    * Neither the names of the copyright holders nor the names of their
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT

#include "codepoint.h"

#include <cstdint>

namespace
{

    bool lookup(const uint32_t* table, char c)
    {
        return table[c >> 5] & (1 << (c & 0x1f));
    }

}

namespace litehtml
{

    bool is_ascii_codepoint(char c)
    {
        return (static_cast<unsigned char>(c) < 128);
    }

    bool is_url_reserved_codepoint(char c)
    {
        static const uint32_t reserved_lookup[] = {0x00000000, 0xac009fda, 0x28000001, 0x00000000};

        if(!is_ascii_codepoint(c))
        {
            return false;
        }
        return lookup(reserved_lookup, c);
    }

    bool is_url_scheme_codepoint(char c)
    {
        static const uint32_t scheme_lookup[] = {
            0x00000000,
            0x03ff6800,
            0x07fffffe,
            0x07fffffe,
        };

        if(!is_ascii_codepoint(c))
        {
            return false;
        }
        return lookup(scheme_lookup, c);
    }

}

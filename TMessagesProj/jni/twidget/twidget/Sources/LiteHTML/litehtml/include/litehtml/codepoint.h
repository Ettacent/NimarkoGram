/* Modifications Copyright (C) 2026 Ettacent */

// Copyright (C) 2020-2021 Primate Labs Inc.
//    * Redistributions of source code must retain the above copyright
// copyright notice, this list of conditions and the following disclaimer
//    * Neither the names of the copyright holders nor the names of their
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT

#ifndef LITEHTML_CODEPOINT_H
#define LITEHTML_CODEPOINT_H

namespace litehtml
{

    bool is_ascii_codepoint(char c);

    bool is_url_reserved_codepoint(char c);

    bool is_url_scheme_codepoint(char c);

}

#endif

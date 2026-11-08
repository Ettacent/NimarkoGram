/* Modifications Copyright (C) 2026 Ettacent */

// Copyright (C) 2020-2021 Primate Labs Inc.
//    * Redistributions of source code must retain the above copyright
// copyright notice, this list of conditions and the following disclaimer
//    * Neither the names of the copyright holders nor the names of their
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT

#ifndef LITEHTML_URL_PATH_H
#define LITEHTML_URL_PATH_H

#include <string>
namespace litehtml
{

    bool is_url_path_absolute(const std::string& path);

    std::string url_path_directory_name(const std::string& path);

    std::string url_path_base_name(const std::string& path);

    std::string url_path_append(const std::string& base, const std::string& path);

    std::string url_path_resolve(const std::string& base, const std::string& path);

}

#endif

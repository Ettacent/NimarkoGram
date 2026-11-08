/* Modifications Copyright (C) 2026 Ettacent */

// Copyright (C) 2020-2021 Primate Labs Inc.
//    * Redistributions of source code must retain the above copyright
// copyright notice, this list of conditions and the following disclaimer
//    * Neither the names of the copyright holders nor the names of their
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT

#include "url_path.h"

namespace litehtml
{

    bool is_url_path_absolute(const std::string& path)
    {
        return path.length() > 0 && path[0] == '/';
    }

    std::string url_path_directory_name(const std::string& path)
    {
        size_t offset = path.find_last_of('/');
        if(offset == std::string::npos)
        {
            return ".";
        }
        return path.substr(0, offset + 1);
    }

    std::string url_path_base_name(const std::string& path)
    {
        size_t offset = path.find_last_of('/');
        if(offset == std::string::npos)
        {
            return path;
        }
        return path.substr(offset + 1);
    }

    std::string url_path_append(const std::string& base, const std::string& path)
    {
        std::string result(base);

        if(!result.empty() && !path.empty() && result.back() != '/')
        {
            result.append(1, '/');
        }

        result.append(path);

        return result;
    }

    std::string url_path_resolve(const std::string& base, const std::string& path)
    {

        if(is_url_path_absolute(path))
        {
            return path;
        }

        return url_path_append(url_path_directory_name(base), path);
    }

}

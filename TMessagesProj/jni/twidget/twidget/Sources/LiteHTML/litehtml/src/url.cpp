/* Modifications Copyright (C) 2026 Ettacent */

// Copyright (C) 2020-2021 Primate Labs Inc.
//    * Redistributions of source code must retain the above copyright
// copyright notice, this list of conditions and the following disclaimer
//    * Neither the names of the copyright holders nor the names of their
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT

#include "url.h"

#include <sstream>
#include <algorithm>
#include "codepoint.h"
#include "url_path.h"
#include <iomanip>

namespace litehtml
{

    url::url(const std::string& str) :
        str_(str)
    {
        std::string tmp = str_;

        size_t offset = tmp.find(':');
        if(offset != std::string::npos)
        {
            bool valid_scheme = true;
            for(size_t i = 0; i < offset; i++)
            {
                if(!is_url_scheme_codepoint(tmp[i]))
                {
                    valid_scheme = false;
                    break;
                }
            }
            if(valid_scheme)
            {
                scheme_ = tmp.substr(0, offset);
                tmp     = tmp.substr(offset + 1);
            }
        }


        if(tmp.size() >= 2 && tmp[0] == '/' && tmp[1] == '/')
        {
            tmp        = tmp.substr(2);
            offset     = tmp.size();
            offset     = std::min(offset, tmp.find('/'));
            offset     = std::min(offset, tmp.find('?'));
            offset     = std::min(offset, tmp.find('#'));
            authority_ = tmp.substr(0, offset);
            tmp        = tmp.substr(offset);

        }

        offset = tmp.find('#');
        if(offset != std::string::npos)
        {
            fragment_ = tmp.substr(offset + 1);
            tmp       = tmp.substr(0, offset);
        }

        offset = tmp.find('?');
        if(offset != std::string::npos)
        {
            query_ = tmp.substr(offset + 1);
            tmp    = tmp.substr(0, offset);
        }

        path_ = tmp;
    }

    url::url(const std::string& scheme, const std::string& authority, const std::string& path, const std::string& query,
             const std::string& fragment) :
        scheme_(scheme),
        authority_(authority),
        path_(path),
        query_(query),
        fragment_(fragment)
    {
        std::stringstream tss;

        if(!scheme_.empty())
        {
            tss << scheme_ << ":";
        }
        if(!authority_.empty())
        {
            tss << "//" << authority_;
        }
        if(!path_.empty())
        {
            tss << path_;
        }
        if(!query_.empty())
        {
            tss << "?" << query_;
        }
        if(!fragment_.empty())
        {
            tss << "#" << fragment_;
        }
        str_ = tss.str();
    }

    std::string url::encode(const std::string& str)
    {
        std::ostringstream encoded;
        encoded << std::hex << std::uppercase;

        for(unsigned char c : str)
        {
            if(isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~')
            {
                encoded << c;
            } else
            {
                encoded << '%' << std::setw(2) << static_cast<int>(c);
            }
        }

        return encoded.str();
    }

    std::string url::decode(const std::string& str)
    {
        std::string decoded;
        size_t      i = 0;

        while(i < str.size())
        {
            char c = str[i];
            if(c == '%')
            {
                if(i + 2 >= str.size())
                {
                    break;
                }

                char hex[3]  = {str[i + 1], str[i + 2], '\0'};
                c            = static_cast<char>(std::strtol(hex, nullptr, 16));
                i           += 2;
            } else if(c == '+')
            {
                c = ' ';
            }
            decoded += c;
            i++;
        }

        return decoded;
    }

    url resolve(const url& b, const url& r)
    {

        if(r.has_scheme())
        {
            return r;
        }
        if(r.has_authority())
        {
            return {b.scheme(), r.authority(), r.path(), r.query(), r.fragment()};
        }
        if(r.has_path())
        {


            if(is_url_path_absolute(r.path()))
            {
                return {b.scheme(), b.authority(), r.path(), r.query(), r.fragment()};
            }
            std::string path = url_path_resolve(b.path(), r.path());
            return {b.scheme(), b.authority(), path, r.query(), r.fragment()};
        }
        if(r.has_query())
        {
            return {b.scheme(), b.authority(), b.path(), r.query(), r.fragment()};
        }
        return {b.scheme(), b.authority(), b.path(), b.query(), r.fragment()};
    }

}

/* Modifications Copyright (C) 2026 Ettacent */

// Copyright (C) 2020-2021 Primate Labs Inc.
//    * Redistributions of source code must retain the above copyright
// copyright notice, this list of conditions and the following disclaimer
//    * Neither the names of the copyright holders nor the names of their
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT

#ifndef LITEHTML_URL_H
#define LITEHTML_URL_H

#include <string>


namespace litehtml
{

    class url
    {
      public:
        url() = default;

        explicit url(const std::string& str);

        url(const std::string& scheme, const std::string& authority, const std::string& path, const std::string& query,
            const std::string& fragment);

        const std::string& str() const
        {
            return str_;
        }

        const std::string& scheme() const
        {
            return scheme_;
        }

        bool has_scheme() const
        {
            return !scheme_.empty();
        }

        const std::string& authority() const
        {
            return authority_;
        }

        bool has_authority() const
        {
            return !authority_.empty();
        }

        const std::string& path() const
        {
            return path_;
        }

        bool has_path() const
        {
            return !path_.empty();
        }

        const std::string& query() const
        {
            return query_;
        }

        bool has_query() const
        {
            return !query_.empty();
        }

        const std::string& fragment() const
        {
            return fragment_;
        }

        bool has_fragment() const
        {
            return !fragment_.empty();
        }

        static std::string encode(const std::string& str);
        static std::string decode(const std::string& str);

      protected:
        std::string str_;


        bool absolute_ = false;

        std::string scheme_;

        std::string authority_;

        std::string path_;

        std::string query_;

        std::string fragment_;
    };


    url resolve(const url& base, const url& reference);

}

#endif

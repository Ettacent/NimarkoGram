/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_ENCODINGS_H
#define LITEHTML_ENCODINGS_H

#include <string>
namespace litehtml
{

    enum class encoding
    {
        null,
        utf_8,

        ibm866,
        iso_8859_2,
        iso_8859_3,
        iso_8859_4,
        iso_8859_5,
        iso_8859_6,
        iso_8859_7,
        iso_8859_8,
        iso_8859_8_i,
        iso_8859_10,
        iso_8859_13,
        iso_8859_14,
        iso_8859_15,
        iso_8859_16,
        koi8_r,
        koi8_u,
        macintosh,
        windows_874,
        windows_1250,
        windows_1251,
        windows_1252,
        windows_1253,
        windows_1254,
        windows_1255,
        windows_1256,
        windows_1257,
        windows_1258,
        x_mac_cyrillic,

        gbk,
        gb18030,
        big5,
        euc_jp,
        iso_2022_jp,
        shift_jis,
        euc_kr,

        replacement,
        utf_16be,
        utf_16le,
        x_user_defined
    };

    enum class confidence
    {
        tentative,
        certain,
    };

    struct estring : std::string
    {
        litehtml::encoding   encoding;
        litehtml::confidence confidence;

        estring(const std::string& str, litehtml::encoding encoding = encoding::null,
                litehtml::confidence confidence = confidence::certain) :
            std::string(str),
            encoding(encoding),
            confidence(confidence)
        {
        }

        estring(const char* str) :
            std::string(str),
            encoding(encoding::null),
            confidence(confidence::certain)
        {
        }
    };

    encoding bom_sniff(const std::string& str);
    void     encoding_sniffing_algorithm(estring& str);

    encoding get_encoding(std::string label);
    encoding extract_encoding_from_meta_element(std::string str);

    void        decode(std::string input, encoding coding, std::string& output);
    std::string decode(std::string input, encoding coding);

}

#endif

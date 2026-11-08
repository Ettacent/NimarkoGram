/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_CSS_TOKENIZER_H
#define LITEHTML_CSS_TOKENIZER_H

#include <cstdio>
#include <string>
#include <utility>
#include <vector>

namespace litehtml
{

    enum css_token_type
    {
        WHITESPACE = ' ',

        _EOF          = EOF,
        LEFT_BRACE    = '{',
        RIGHT_BRACE   = '}',
        LEFT_BRACKET  = '[',
        RIGHT_BRACKET = ']',
        LEFT_PAREN    = '(',
        RIGHT_PAREN   = ')',
        COLON         = ':',
        SEMICOLON     = ';',
        COMMA         = ',',
        BANG          = '!',
        DOT           = '.',
        AMPERSAND     = '&',

        IDENT = -20,
        FUNCTION,
        AT_KEYWORD,
        HASH,
        STRING,
        BAD_STRING,
        URL,
        BAD_URL,
        NUMBER,
        PERCENTAGE,
        DIMENSION,
        CDO,
        CDC,

        CV_FUNCTION = -100,
        CURLY_BLOCK  = -100 - '{',
        ROUND_BLOCK  = -100 - '(',
        SQUARE_BLOCK = -100 - '['
    };

    enum css_number_type
    {
        css_number_integer,
        css_number_number
    };

    enum css_hash_type
    {
        css_hash_unrestricted,
        css_hash_id
    };

    class css_token
    {
        std::string m_str;

      public:
        css_token(css_token_type type = css_token_type(), float number = 0,
                  css_number_type number_type = css_number_integer, std::string str = "") :
            m_str(std::move(str)),
            type(type),
            n{number, number_type}
        {
        }

        css_token(css_token_type type, const std::string& str) :
            m_str(str),
            type(type),
            n()
        {
        }

        css_token(const css_token& token) :
            m_str(token.str()),
            type(token.type),
            repr(token.repr)
        {
            switch(type)
            {
            case HASH:
                hash_type = token.hash_type;
                break;

            case NUMBER:
            case PERCENTAGE:
            case DIMENSION:
                n = token.n;
                break;

            case CV_FUNCTION:
            case CURLY_BLOCK:
            case ROUND_BLOCK:
            case SQUARE_BLOCK:
                value = std::vector<css_token>(token.value);
                break;

            default:;
            }
        }

        css_token(css_token&& token) noexcept :
            m_str(std::move(token.m_str)),
            type(token.type),
            value(std::move(token.value)),
            repr(std::move(token.repr))
        {
            switch(type)
            {
            case HASH:
                hash_type = token.hash_type;
                break;

            case NUMBER:
            case PERCENTAGE:
            case DIMENSION:
                n = token.n;
                break;

            default:;
            }
        }

        css_token& operator=(const css_token& token) = default;
        css_token& operator=(css_token&& token) noexcept = default;

        bool is_component_value() const
        {
            return type <= CV_FUNCTION;
        }

        std::string ident() const;
        std::string get_repr(bool insert_spaces = false) const;

        union {
            css_token_type type;
            int            ch;
        };

        std::string& str()
        {
            return m_str;
        }
        const std::string& str() const
        {
            return m_str;
        }
        std::string& name()
        {
            return m_str;
        }
        const std::string& name() const
        {
            return m_str;
        }
        std::string& unit()
        {
            return m_str;
        }
        const std::string& unit() const
        {
            return m_str;
        }

        struct number
        {
            float           number;
            css_number_type number_type;
        };
        union {
            css_hash_type hash_type;
            number        n;
        };
        std::vector<css_token> value;

        std::string repr;
    };

    using css_token_vector = std::vector<css_token>;
    std::string get_repr(const css_token_vector& tokens, int index = 0, int count = -1, bool insert_spaces = false);

    class css_tokenizer
    {
      public:
        css_tokenizer(const std::string& input) :
            str(input)
        {
        }

        css_token_vector tokenize();

        static double convert_string_to_number(const std::string& str);

      private:
        std::string str;

        int index = 0;

        int current_char = 0;

      private:
        static bool is_whitespace(int ch);
        static bool is_non_printable_code_point(int ch);
        static bool is_ident_start_code_point(int ch);
        static bool is_ident_code_point(int ch);

        struct three_chars
        {
            int _1, _2, _3;
        };

        int         consume_char();
        void        unconsume_char();
        int         peek_char();
        three_chars peek_chars();

        void      consume_comments();
        int       consume_escaped_code_point();
        css_token consume_string_token(int ending_code_point);

        static bool would_start_ident_sequence(three_chars chars);
        std::string consume_ident_sequence();

        static bool   would_start_a_number(int x, int y, int z);
        double        consume_number(css_number_type& number_type);
        css_token     consume_numeric_token();

        void      consume_remnants_of_bad_url();
        css_token consume_url_token();

        css_token consume_ident_like_token();
        css_token consume_token();
    };

    void                    css_parse_error(const std::string& msg);
    inline css_token_vector tokenize(const std::string& str)
    {
        return css_tokenizer(str).tokenize();
    }

}

#endif

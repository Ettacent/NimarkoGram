/* Modifications Copyright (C) 2026 Ettacent */

#include "html.h"
#include "css_tokenizer.h"
#include "utf8_strings.h"

namespace litehtml
{

    void css_parse_error(const std::string&  )
    {
    }

    std::string css_token::ident() const
    {
        if(type != IDENT)
        {
            return "";
        }
        return name().substr(0, 2) == "--" ? name() : lowcase(name());
    }

    char mirror(char c)
    {
        if(c == '{')
        {
            return '}';
        }
        if(c == '[')
        {
            return ']';
        }
        if(c == '(')
        {
            return ')';
        }
        return c;
    }

    std::string css_token::get_repr(bool insert_spaces) const
    {
        if(!is_component_value())
        {
            return repr;
        }

        using litehtml::get_repr;
        if(type == CV_FUNCTION)
        {
            return name() + '(' + get_repr(value, 0, -1, insert_spaces) + ')';
        }

        char opening_bracket = static_cast<char>(-type - 100);
        char closing_bracket = mirror(opening_bracket);
        return opening_bracket + get_repr(value, 0, -1, insert_spaces) + closing_bracket;
    }

    std::string get_repr(const css_token_vector& tokens, int index, int count, bool insert_spaces)
    {
        if(count == -1)
        {
            count = static_cast<int>(tokens.size()) - index;
        }
        std::string str;
        std::string space = insert_spaces ? " " : "";
        for(int i = index; i < index + count; i++)
        {
            str += tokens[i].get_repr(insert_spaces) + space;
        }
        if(insert_spaces)
        {
            remove(str, -1);
        }
        return str;
    }

    bool css_tokenizer::is_whitespace(int ch)
    {
        return ch == '\n' || ch == '\t' || ch == ' ';
    }

    bool css_tokenizer::is_non_printable_code_point(int ch)
    {
        return (ch >= 0 && ch <= 8) || ch == 0xB || (ch >= 0xE && ch <= 0x1F) || ch == 0x7F;
    }

    bool css_tokenizer::is_ident_start_code_point(int ch)
    {
        return is_letter(ch) || ch >= 0x80 || ch == '_';
    }

    bool css_tokenizer::is_ident_code_point(int ch)
    {
        return is_ident_start_code_point(ch) || is_digit(ch) || ch == '-';
    }

    int css_tokenizer::consume_char()
    {
        return current_char = read_utf8_char(str, index);
    }

    void css_tokenizer::unconsume_char()
    {
        if(current_char == 0)
        {
            return;
        }

        prev_utf8_char(str, index);
    }

    int css_tokenizer::peek_char()
    {
        int i = index;
        return read_utf8_char(str, i);
    }

    css_tokenizer::three_chars css_tokenizer::peek_chars()
    {
        three_chars chars = {};
        int         i     = index;

        chars._1 = read_utf8_char(str, i);
        chars._2 = read_utf8_char(str, i);
        chars._3 = read_utf8_char(str, i);
        return chars;
    }

    void css_tokenizer::consume_comments()
    {
        while(true)
        {
            if(str[index] == '/' && str[index + 1] == '*')
            {
                int i = static_cast<int>(str.find("*/", index + 2));

                if(i != -1)
                {
                    index = i + 2;
                } else
                {
                    index = static_cast<int>(str.size());
                    css_parse_error("eof in comment");
                    break;
                }
            } else
            {
                break;
            }
        }
    }

    int css_tokenizer::consume_escaped_code_point()
    {
        int ch = consume_char();

        if(is_hex_digit(ch))
        {
            int number = digit_value(ch);
            int max = 5;
            while(max-- > 0 && is_hex_digit(str[index]))
            {
                ch     = consume_char();
                number = number * 16 + digit_value(ch);
            }
            if(is_whitespace(str[index]))
            {
                consume_char();
            }
            if(number == 0 || is_surrogate(number) || number > 0x10FFFF)
            {
                return 0xFFFD;
            }
            return number;
        }
        if(ch == 0)
        {
            css_parse_error("eof in escaped codepoint");
            return 0xFFFD;
        }
        return ch;
    }

    css_token css_tokenizer::consume_string_token(int ending_code_point)
    {
        css_token token(STRING);

        while(true)
        {
            int ch = consume_char();
            switch(ch)
            {
            case 0:
                css_parse_error("eof in string");
                return token;
            case '\n':
                css_parse_error("newline in string");
                unconsume_char();
                return {BAD_STRING};
            case '\\':
                if(str[index] == 0)
                {
                    break;
                }
                else if(str[index] == '\n')
                {
                    index++;
                }
                else
                {
                    append_char(token.str(), consume_escaped_code_point());
                }
                break;
            default:
                if(ch == ending_code_point)
                {
                    return token;
                } else
                {
                    append_char(token.str(), ch);
                }
                break;
            }
        }
    }

    bool css_tokenizer::would_start_ident_sequence(three_chars chars)
    {
        int c1 = chars._1;
        int c2 = chars._2;
        int c3 = chars._3;

        if(c1 == '-')
        {
            return is_ident_start_code_point(c2) || c2 == '-' || (c2 == '\\' && c3 != '\n');
        }
        if(is_ident_start_code_point(c1))
        {
            return true;
        }
        if(c1 == '\\')
        {
            return c2 != '\n';
        }
        return false;
    }

    std::string css_tokenizer::consume_ident_sequence()
    {
        std::string result;

        while(true)
        {
            int ch = consume_char();

            if(is_ident_code_point(ch))
            {
                append_char(result, ch);
            }

            else if(ch == '\\' && str[index] != '\n')
            {
                append_char(result, consume_escaped_code_point());
            }

            else
            {
                unconsume_char();
                return result;
            }
        }
    }

    bool css_tokenizer::would_start_a_number(int x, int y, int z)
    {
        if(x == '+' || x == '-')
        {
            if(is_digit(y))
            {
                return true;
            }
            if(y == '.' && is_digit(z))
            {
                return true;
            }
            return false;
        }
        if(x == '.')
        {
            return is_digit(y);
        }
        return is_digit(x);
    }

    double css_tokenizer::convert_string_to_number(const std::string& str)
    {
        const char* p = str.c_str();


        double s = 1;
        if(*p == '-')
        {
            s = -1, p++;
        } else if(*p == '+')
        {
            p++;
        }

        double i = 0;
        while(is_digit(*p))
        {
            i = i * 10 + digit_value(*p++);
        }

        if(*p == '.')
        {
            p++;
        }

        double f = 0;
        double d = 0;
        while(is_digit(*p))
        {
            f = f * 10 + digit_value(*p++), d++;
        }

        if(*p == 'e' || *p == 'E')
        {
            p++;
        }

        double t = 1;
        if(*p == '-')
        {
            t = -1, p++;
        } else if(*p == '+')
        {
            p++;
        }

        double e = 0;
        while(is_digit(*p))
        {
            e = e * 10 + digit_value(*p++);
        }

        return s * (i + f * pow(10, -d)) * pow(10, t * e);
    }

    double css_tokenizer::consume_number(css_number_type& type)
    {
        type = css_number_integer;
        std::string repr;

        if(is_one_of(str[index], '+', '-'))
        {
            append_char(repr, str[index++]);
        }

        while(is_digit(str[index]))
        {
            append_char(repr, str[index++]);
        }

        if(str[index] == '.' && is_digit(str[index + 1]))
        {
            append_char(repr, str[index++]);
            append_char(repr, str[index++]);
            type = css_number_number;
            while(is_digit(str[index]))
            {
                append_char(repr, str[index++]);
            }
        }

        bool a = lowcase(str[index]) == 'e' && is_one_of(str[index + 1], '+', '-') && is_digit(str[index + 2]);
        bool b = lowcase(str[index]) == 'e' && is_digit(str[index + 1]);

        if(a || b)
        {
            append_char(repr, str[index++]);
            append_char(repr, str[index++]);
            if(a)
            {
                append_char(repr, str[index++]);
            }
            type = css_number_number;
            while(is_digit(str[index]))
            {
                append_char(repr, str[index++]);
            }
        }

        double value = convert_string_to_number(repr);

        return value;
    }

    css_token css_tokenizer::consume_numeric_token()
    {
        css_number_type type;

        auto number = static_cast<float>(consume_number(type));

        if(would_start_ident_sequence(peek_chars()))
        {
            css_token token(DIMENSION, number, type);

            token.unit() = consume_ident_sequence();

            return token;
        }

        if(str[index] == '%')
        {
            index++;
            return {PERCENTAGE, number};
        }

        return {NUMBER, number, type};
    }

    void css_tokenizer::consume_remnants_of_bad_url()
    {
        while(true)
        {
            int ch = consume_char();
            if(ch == ')' || ch == 0)
            {
                return;
            }
            if(ch == '\\' && str[index] != '\n')
            {
                consume_escaped_code_point();
            }
        }
    }

    css_token css_tokenizer::consume_url_token()
    {
        css_token token(URL);

        while(is_whitespace(str[index]))
        {
            index++;
        }

        while(true)
        {
            int ch = consume_char();
            switch(ch)
            {
            case ')':
                return token;

            case 0:
                css_parse_error("eof in unquoted url");
                return token;

            case '\n':
            case '\t':
            case ' ':
                while(is_whitespace(str[index]))
                {
                    index++;
                }
                if(str[index] == ')' || str[index] == 0)
                {
                    if(str[index] == 0)
                    {
                        css_parse_error("eof in unquoted url");
                    } else
                    {
                        index++;
                    }
                    return token;
                }
                consume_remnants_of_bad_url();
                return {BAD_URL};

            case '"':
            case '\'':
            case '(':
            bad_url:
                css_parse_error("invalid char in unquoted url");
                consume_remnants_of_bad_url();
                return {BAD_URL};

            case '\\':
                if(str[index] != '\n')
                {
                    append_char(token.str(), consume_escaped_code_point());
                }
                else
                {
                    css_parse_error("escaped newline in unquoted url");
                    consume_remnants_of_bad_url();
                    return {BAD_URL};
                }
                break;

            default:
                if(is_non_printable_code_point(ch))
                {
                    goto bad_url;
                } else
                {
                    append_char(token.str(), ch);
                }
                break;
            }
        }
    }

    css_token css_tokenizer::consume_ident_like_token()
    {
        auto string = consume_ident_sequence();

        if(lowcase(string) == "url" && str[index] == '(')
        {
            index++;

            while(is_whitespace(str[index]))
            {
                index++;
            }

            if(is_one_of(str[index], '"', '\''))
            {
                if(is_whitespace(str[index - 1]))
                {
                    index--;
                }
                return {FUNCTION, string};
            }
            return consume_url_token();
        }

        if(str[index] == '(')
        {
            index++;
            return {FUNCTION, string};
        }

        return {IDENT, string};
    }

    css_token css_tokenizer::consume_token()
    {
        consume_comments();

        css_token token;
        int       start = index;

        int         ch   = consume_char();
        three_chars next = {};

        switch(ch)
        {
        case '\n':
        case '\t':
        case ' ':
            while(is_whitespace(str[index]))
            {
                index++;
            }
            token.type = WHITESPACE;
            break;

        case '"':
        case '\'':
            token = consume_string_token(ch);
            break;

        case '#':
            if(is_ident_code_point(peek_char()) || (str[index] == '\\' && str[index + 1] != '\n'))
            {
                token.type = HASH;
                token.hash_type = would_start_ident_sequence(peek_chars()) ? css_hash_id : css_hash_unrestricted;
                token.name() = consume_ident_sequence();
            } else
            {
                token.ch = ch;
            }
            break;

        case '+':
        case '.':
            next = peek_chars();
            if(would_start_a_number(ch, next._1, next._2))
            {
                unconsume_char();
                token = consume_numeric_token();
            } else
            {
                token.ch = ch;
            }
            break;

        case '-':
            next = peek_chars();
            if(would_start_a_number(ch, next._1, next._2))
            {
                unconsume_char();
                token = consume_numeric_token();
            }
            else if(next._1 == '-' && next._2 == '>')
            {
                index      += 2;
                token.type  = CDC;
            }
            else if(would_start_ident_sequence({ch, next._1, next._2}))
            {
                unconsume_char();
                token = consume_ident_like_token();
            } else
            {
                token.ch = ch;
            }
            break;

        case '<':
            if(match(str, index, "!--"))
            {
                index      += 3;
                token.type  = CDO;
            } else
            {
                token.ch = ch;
            }
            break;

        case '@':
            if(would_start_ident_sequence(peek_chars()))
            {
                token.type   = AT_KEYWORD;
                token.name() = consume_ident_sequence();
            } else
            {
                token.ch = ch;
            }
            break;

        case '\\':
            if(str[index] != '\n')
            {
                unconsume_char();
                token = consume_ident_like_token();
            } else
            {
                css_parse_error("escaped newline outside a string");
                token.ch = ch;
            }
            break;

        case 0:
            token.type = _EOF;
            break;

        default:
            if(is_digit(ch))
            {
                unconsume_char();
                token = consume_numeric_token();
            } else if(is_ident_start_code_point(ch))
            {
                unconsume_char();
                token = consume_ident_like_token();
            } else
            {
                token.ch = ch;
            }
        }

        token.repr = str.substr(start, index - start);
        return token;
    }

    css_token_vector css_tokenizer::tokenize()
    {
        css_token_vector tokens;
        while(true)
        {
            css_token token = consume_token();
            if(token.type == EOF)
            {
                break;
            }
            tokens.push_back(token);
        }
        return tokens;
    }

}

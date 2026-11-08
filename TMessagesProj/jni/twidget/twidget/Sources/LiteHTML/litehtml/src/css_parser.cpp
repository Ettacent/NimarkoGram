/* Modifications Copyright (C) 2026 Ettacent */

#include "encodings.h"
#include "html.h"
#include "css_parser.h"

namespace litehtml
{

    void filter_code_points(std::string& input)
    {
        const char* xFFFD = "\xEF\xBF\xBD";

        size_t null_count = std::count(input.begin(), input.end(), 0);

        std::string result(input.size() + 2 * null_count, 0);

        for(int i = 0, j = 0; i < static_cast<int>(input.size()); i++)
        {
            switch(input[i])
            {
            case '\r':
                result[j++] = '\n';
                if(i + 1 < static_cast<int>(input.size()) && input[i + 1] == '\n')
                {
                    i++;
                }
                break;
            case '\f':
                result[j++] = '\n';
                break;
            case 0:
                memcpy(&result[j], xFFFD, 3);
                j += 3;
                break;
            default:
                result[j++] = input[i];
            }
        }

        result.resize(strlen(result.c_str()));
        input = result;
    }

    static const size_t kLargeSize = 50;
    static void         remove_whitespace_large(css_token_vector& tokens, const keep_whitespace_fn& keep_whitespace);
    static void         remove_whitespace_small(css_token_vector& tokens, const keep_whitespace_fn& keep_whitespace);

    void remove_whitespace_large(css_token_vector& tokens, const keep_whitespace_fn& keep_whitespace)
    {
        std::vector<int> keep_idx;
        keep_idx.reserve(tokens.size());
        for(int i = 0; i < static_cast<int>(tokens.size()); ++i)
        {
            auto& tok  = tokens[i];
            bool  keep = true;
            if(tok.type == ' ')
            {
                const auto& left  = i > 0 ? tokens[i - 1] : css_token();
                const auto& right = at(tokens, i + 1);
                keep              = keep_whitespace && keep_whitespace(left, right);
            } else if(tok.is_component_value())
            {
                if(tok.value.size() > kLargeSize)
                {
                    remove_whitespace_large(tok.value, keep_whitespace);
                } else
                {
                    remove_whitespace_small(tok.value, keep_whitespace);
                }
            }
            if(keep)
            {
                keep_idx.push_back(i);
            }
        }

        if(keep_idx.size() == tokens.size())
        {
            return;
        }
        css_token_vector tmp;
        tmp.reserve(keep_idx.size());
        for(auto idx : keep_idx)
        {
            tmp.push_back(std::move(tokens[idx]));
        }
        tokens.swap(tmp);
    }

    void remove_whitespace_small(css_token_vector& tokens, const keep_whitespace_fn& keep_whitespace)
    {
        for(int i = 0; i < static_cast<int>(tokens.size()); i++)
        {
            auto& tok = tokens[i];
            if(tok.type == ' ')
            {
                const auto& left  = i > 0 ? tokens[i - 1] : css_token();
                const auto& right = at(tokens, i + 1);
                bool        keep  = keep_whitespace && keep_whitespace(left, right);
                if(!keep)
                {
                    remove(tokens, i), i--;
                }
            } else if(tok.is_component_value())
            {
                if(tok.value.size() > kLargeSize)
                {
                    remove_whitespace_large(tok.value, keep_whitespace);
                } else
                {
                    remove_whitespace_small(tok.value, keep_whitespace);
                }
            }
        }
    }

    void remove_whitespace(css_token_vector& tokens, const keep_whitespace_fn& keep_whitespace)
    {
        if(tokens.size() > kLargeSize)
        {
            remove_whitespace_large(tokens, keep_whitespace);
        } else
        {
            remove_whitespace_small(tokens, keep_whitespace);
        }
    }

    void componentize(css_token_vector& tokens)
    {
        css_parser       parser(tokens);
        css_token_vector result;
        while(true)
        {
            css_token tok = parser.consume_component_value();
            if(tok.type == EOF)
            {
                break;
            }
            result.push_back(std::move(tok));
        }
        tokens = std::move(result);
    }

    css_token_vector normalize(css_token_vector input, int options, const keep_whitespace_fn& keep_whitespace)
    {
        if(options & f_componentize)
        {
            componentize(input);
        }
        if(options & f_remove_whitespace)
        {
            remove_whitespace(input, keep_whitespace);
        }
        return input;
    }
    css_token_vector normalize(std::string input, int options, const keep_whitespace_fn& keep_whitespace)
    {
        filter_code_points(input);
        auto tokens = tokenize(input);
        return normalize(tokens, options, keep_whitespace);
    }

    raw_rule::vector css_parser::parse_stylesheet(const std::string& input, bool top_level)
    {
        std::string str = decode(input, encoding::utf_8);

        auto tokens = normalize(str);

        return parse_stylesheet(tokens, top_level);
    }
    raw_rule::vector css_parser::parse_stylesheet(const css_token_vector& input, bool top_level)
    {
        return css_parser(input).consume_list_of_rules(top_level);
    }

    css_token css_parser::next_token()
    {
        if(m_index == static_cast<int>(m_tokens.size()))
        {
            return css_token_type(EOF);
        }
        return m_tokens[m_index++];
    }

    css_token css_parser::peek_token()
    {
        if(m_index == static_cast<int>(m_tokens.size()))
        {
            return css_token_type(EOF);
        }
        return m_tokens[m_index];
    }

    raw_rule::vector css_parser::consume_list_of_rules(bool top_level)
    {
        raw_rule::vector rules;
        raw_rule::ptr    rule;

        while(true)
        {
            css_token token = next_token();

            switch(token.type)
            {
            case WHITESPACE:
                break;

            case EOF:
                return rules;

            case CDO:
            case CDC:
                if(top_level)
                {
                    break;
                }

                m_index--;
                rule = consume_qualified_rule();
                if(rule)
                {
                    rules.push_back(rule);
                }
                break;

            case AT_KEYWORD:
                m_index--;
                rule = consume_at_rule();
                if(rule)
                {
                    rules.push_back(rule);
                }
                break;

            default:
                m_index--;
                rule = consume_qualified_rule();
                if(rule)
                {
                    rules.push_back(rule);
                }
                break;
            }
        }
    }

    raw_rule::ptr css_parser::consume_qualified_rule()
    {
        raw_rule::ptr rule = std::make_shared<raw_rule>(raw_rule::qualified);

        while(true)
        {
            css_token token = next_token();

            switch(token.type)
            {
            case EOF:
                css_parse_error("eof in qualified rule");
                return nullptr;
            case '{':
                rule->block = consume_simple_block('{');
                return rule;
            case CURLY_BLOCK:
                rule->block = token;
                return rule;
            default:
                m_index--;
                css_token value = consume_component_value();
                rule->prelude.push_back(value);
            }
        }
    }

    raw_rule::ptr css_parser::consume_at_rule()
    {
        css_token     token = next_token();
        raw_rule::ptr rule  = std::make_shared<raw_rule>(raw_rule::at, token.str());

        while(true)
        {
            token = next_token();

            switch(token.type)
            {
            case ';':
                return rule;
            case EOF:
                css_parse_error("eof in at-rule");
                return rule;
            case '{':
                rule->block = consume_simple_block('{');
                return rule;
            case CURLY_BLOCK:
                rule->block = token;
                return rule;
            default:
                m_index--;
                css_token value = consume_component_value();
                rule->prelude.push_back(value);
            }
        }
    }

    char mirror(char c);

    css_token css_parser::consume_simple_block(char opening_bracket)
    {
        auto      block_type = css_token_type(-100 - opening_bracket);
        css_token block(block_type);

        char closing_bracket = mirror(opening_bracket);

        while(true)
        {
            css_token token = next_token();

            if(token.type == closing_bracket)
            {
                return block;
            }
            if(token.type == EOF)
            {
                css_parse_error("eof in simple block");
                return block;
            }
            m_index--;
            css_token val = consume_component_value();
            block.value.push_back(val);
        }
    }

    css_token css_parser::consume_component_value()
    {
        css_token token = next_token();

        switch(token.type)
        {
        case '{':
        case '[':
        case '(':
            return consume_simple_block(static_cast<char>(token.ch));

        case FUNCTION:
            return consume_function(token.name());

        default:
            return token;
        }
    }

    css_token css_parser::consume_function(const std::string& name)
    {
        css_token function(CV_FUNCTION, name);

        while(true)
        {
            css_token token = next_token();

            switch(token.type)
            {
            case ')':
                return function;

            case EOF:
                css_parse_error("eof in function");
                return function;

            default:
                m_index--;
                css_token val = consume_component_value();
                function.value.push_back(val);
            }
        }
    }


    void trim_whitespace(css_token_vector& tokens)
    {
        while(at(tokens, 0).type == ' ')
        {
            remove(tokens, 0);
        }
        while(at(tokens, -1).type == ' ')
        {
            remove(tokens, -1);
        }
    }

    raw_declaration css_parser::consume_declaration()
    {
        css_token       token = next_token();
        raw_declaration decl  = {token.name(), {}};
        auto&           value = decl.value;

        while(peek_token().type == ' ')
        {
            next_token();
        }

        if(peek_token().ch != ':')
        {
            css_parse_error("consume_declaration: ':' not found");
            return {};
        }
        next_token();

        while(peek_token().type == ' ')
        {
            next_token();
        }

        while(peek_token().type != EOF)
        {
            value.push_back(consume_component_value());
        }


        trim_whitespace(value);

        if(at(value, -1).ident() == "important" && at(value, -2).ch == '!')
        {
            remove(value, -2, 2);
            decl.important = true;
        }

        trim_whitespace(value);

        return decl;
    }

    void css_parser::consume_style_block_contents(  raw_declaration::vector& decls,
                                                    raw_rule::vector&        rules)
    {
        while(true)
        {
            css_token token = next_token();

            switch(token.type)
            {
            case WHITESPACE:
            case ';':
                break;

            case EOF:
                return;

            case AT_KEYWORD:
                {
                    m_index--;
                    auto rule = consume_at_rule();
                    if(rule)
                    {
                        rules.push_back(rule);
                    }
                    break;
                }
            case IDENT:
                {
                    css_token_vector temp = {token};
                    while(!is_one_of(peek_token().type, ';', EOF))
                    {
                        temp.push_back(consume_component_value());
                    }

                    css_parser parser(temp);
                    auto decl = parser.consume_declaration();
                    if(decl)
                    {
                        decls.push_back(decl);
                    }
                    break;
                }
            case '&':
                {
                    m_index--;
                    auto rule = consume_qualified_rule();
                    if(rule)
                    {
                        rules.push_back(rule);
                    }
                    break;
                }
            default:
                css_parse_error("unexpected token in a style block");
                m_index--;
                while(!is_one_of(peek_token().type, ';', EOF))
                {
                    consume_component_value();
                }
                break;
            }
        }
    }

    std::vector<css_token_vector> parse_comma_separated_list(const css_token_vector& tokens)
    {
        std::vector<css_token_vector> result;

        css_token_vector list;
        for(auto& tok : tokens)
        {
            if(tok.type == ',')
            {
                result.push_back(list);
                list.clear();
                continue;
            }
            list.push_back(tok);
        }
        result.push_back(list);

        return result;
    }

    bool is_any_value(const css_token_vector& tokens)
    {
        if(tokens.empty())
        {
            return false;
        }
        for(auto& tok : tokens)
        {
            if(is_one_of(tok.type, BAD_STRING, BAD_URL, ')', ']', '}'))
            {
                return false;
            }
            if(tok.is_component_value() && !is_any_value(tok.value))
            {
                return false;
            }
        }
        return true;
    }

    bool is_declaration_value(const css_token_vector& tokens, int index)
    {
        if(index >= static_cast<int>(tokens.size()))
        {
            return false;
        }
        for(int i = index; i < static_cast<int>(tokens.size()); i++)
        {
            auto& tok = tokens[i];
            if(is_one_of(tok.type, BAD_STRING, BAD_URL, ')', ']', '}', ';', '!'))
            {
                return false;
            }
            if(tok.is_component_value() && !is_any_value(tok.value))
            {
                return false;
            }
        }
        return true;
    }

    bool skip_whitespace(const css_token_vector& tokens, int& index)
    {
        int start = index;
        while(at(tokens, index).type == ' ')
        {
            index++;
        }
        return index != start;
    }

}

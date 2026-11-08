/* Modifications Copyright (C) 2026 Ettacent */

#include "html.h"
#include "stylesheet.h"
#include "css_parser.h"
#include "document.h"
#include "document_container.h"

namespace litehtml
{

    static bool eval_supports_declaration(const css_token_vector& tokens, document_container* container)
    {
        for(const auto& token : tokens)
        {
            if(token.ch == ';')
            {
                return false;
            }
        }
        style st;
        st.add(tokens, "", container);
        return !st.empty();
    }

    static bool parse_supports_condition(const css_token_vector& tokens, int& index, bool& result,
                                         document_container* container);

    static bool parse_supports_in_parens(const css_token& token, bool& result, document_container* container)
    {
        if(token.type == CV_FUNCTION)
        {
            result = false;
            return true;
        }
        if(token.type != ROUND_BLOCK)
        {
            return false;
        }

        int  index = 0;
        bool value = false;
        if(parse_supports_condition(token.value, index, value, container))
        {
            skip_whitespace(token.value, index);
            if(index == static_cast<int>(token.value.size()))
            {
                result = value;
                return true;
            }
        }
        result = eval_supports_declaration(token.value, container);
        return true;
    }

    static bool parse_supports_condition(const css_token_vector& tokens, int& index, bool& result,
                                         document_container* container)
    {
        auto parse_operand = [&](bool& value) {
            skip_whitespace(tokens, index);
            if(!parse_supports_in_parens(at(tokens, index), value, container))
            {
                return false;
            }
            index++;
            return true;
        };
        auto next_ident = [&]() {
            skip_whitespace(tokens, index);
            return lowcase(at(tokens, index).ident());
        };

        if(next_ident() == "not")
        {
            index++;
            if(!parse_operand(result))
            {
                return false;
            }
            result = !result;
            return true;
        }

        if(!parse_operand(result))
        {
            return false;
        }

        std::string op = next_ident();
        if(op != "and" && op != "or")
        {
            return true;
        }
        while(next_ident() == op)
        {
            index++;
            bool operand = false;
            if(!parse_operand(operand))
            {
                return false;
            }
            result = op == "and" ? result && operand : result || operand;
        }
        return true;
    }

    static bool eval_supports_condition(const css_token_vector& tokens, document_container* container)
    {
        int  index  = 0;
        bool result = false;
        if(!parse_supports_condition(tokens, index, result, container))
        {
            return false;
        }
        skip_whitespace(tokens, index);
        return index == static_cast<int>(tokens.size()) && result;
    }

    template <class Input>
    void css::parse_css_stylesheet(const Input& input, const std::string& baseurl, const std::shared_ptr<document>& doc,
                                   const media_query_list_list::ptr& media, bool top_level)
    {
        if(doc && media)
        {
            doc->add_media_list(media);
        }

        auto rules          = css_parser::parse_stylesheet(input, top_level);
        bool import_allowed = top_level;

        for(const auto& rule : rules)
        {
            if(rule->type == raw_rule::qualified)
            {
                if(parse_style_rule(rule, baseurl, doc, media))
                {
                    import_allowed = false;
                }
                continue;
            }

            if(!doc->container()->sanitize_at_rule(lowcase(rule->name)))
            {
                continue;
            }
            switch(_id(lowcase(rule->name)))
            {
            case _charset_:
                break;

            case _import_:
                if(import_allowed)
                {
                    parse_import_rule(rule, baseurl, doc, media);
                } else
                {
                    css_parse_error("incorrect placement of @import rule");
                }
                break;

            case _media_:
                {
                    if(rule->block.type != CURLY_BLOCK)
                    {
                        break;
                    }
                    auto new_media = media;
                    auto mq_list   = parse_media_query_list(rule->prelude, doc);
                    if(!mq_list.empty())
                    {
                        new_media = std::make_shared<media_query_list_list>(media ? *media : media_query_list_list());
                        new_media->add(mq_list);
                    }
                    parse_css_stylesheet(rule->block.value, baseurl, doc, new_media, false);
                    import_allowed = false;
                    break;
                }

            case _supports_:
                {
                    if(rule->block.type != CURLY_BLOCK)
                    {
                        break;
                    }
                    auto condition = normalize(rule->prelude, f_componentize);
                    if(eval_supports_condition(condition, doc->container()))
                    {
                        parse_css_stylesheet(rule->block.value, baseurl, doc, media, false);
                    }
                    import_allowed = false;
                    break;
                }

            case _layer_:
                {
                    if(rule->block.type == CURLY_BLOCK)
                    {
                        m_layer_depth++;
                        parse_css_stylesheet(rule->block.value, baseurl, doc, media, false);
                        m_layer_depth--;
                        import_allowed = false;
                    }
                    break;
                }

            default:
                css_parse_error("unrecognized rule @" + rule->name);
            }
        }
    }

    void css::parse_import_rule(const raw_rule::ptr& rule, const std::string& baseurl,
                                const std::shared_ptr<document>& doc, const media_query_list_list::ptr& media)
    {
        auto tokens = rule->prelude;
        int  index  = 0;
        skip_whitespace(tokens, index);
        auto        tok = at(tokens, index);
        std::string url;
        auto        parse_string = [](const css_token& tok, std::string& str) {
            if(tok.type != STRING)
            {
                return false;
            }
            str = tok.str();
            return true;
        };
        bool ok = parse_url(tok, url) || parse_string(tok, url);
        if(!ok)
        {
            css_parse_error("invalid @import rule");
            return;
        }
        document_container* container = doc->container();
        std::string         css_text;
        std::string         css_baseurl = baseurl;
        container->import_css(css_text, url, css_baseurl);

        auto new_media = media;
        tokens         = slice(tokens, index + 1);
        auto mq_list   = parse_media_query_list(tokens, doc);
        if(!mq_list.empty())
        {
            new_media = std::make_shared<media_query_list_list>(media ? *media : media_query_list_list());
            new_media->add(mq_list);
        }

        parse_css_stylesheet(css_text, css_baseurl, doc, new_media, true);
    }

    bool css::parse_style_rule(const raw_rule::ptr& rule, const std::string& baseurl,
                               const std::shared_ptr<document>& doc, const media_query_list_list::ptr& media)
    {
        auto list = parse_selector_list(rule->prelude, strict_mode, doc->mode());
        if(list.empty())
        {
            css_parse_error("invalid selector");
            return false;
        }

        style::ptr style = std::make_shared<litehtml::style>();
        style->add(rule->block.value, baseurl, doc->container());

        for(const auto& sel : list)
        {
            sel->m_style       = style;
            sel->m_media_query = media;
            sel->calc_specificity();
            add_selector(sel);
        }
        return true;
    }

    void css::sort_selectors()
    {
        std::sort(m_selectors.begin(), m_selectors.end(),
                  [](const css_selector::ptr& v1, const css_selector::ptr& v2) { return (*v1) < (*v2); });
    }

}

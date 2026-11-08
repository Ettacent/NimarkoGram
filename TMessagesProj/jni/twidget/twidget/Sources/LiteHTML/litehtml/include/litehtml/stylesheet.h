/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_STYLESHEET_H
#define LITEHTML_STYLESHEET_H

#include <utility>

#include "css_selector.h"
#include "css_tokenizer.h"

namespace litehtml
{

    struct raw_declaration
    {
        using vector = std::vector<raw_declaration>;

        std::string name;
        css_token_vector value;

        bool important = false;

        operator bool() const
        {
            return name != "";
        }
    };

    class raw_rule
    {
      public:
        using ptr    = std::shared_ptr<raw_rule>;
        using vector = std::vector<ptr>;

        enum rule_type
        {
            qualified,
            at
        };

        raw_rule(rule_type type, std::string name = {}) :
            type(type),
            name(std::move(name))
        {
        }

        rule_type type;
        std::string name;
        css_token_vector prelude;
        css_token        block;
    };

    class css
    {
        css_selector::vector m_selectors;

      public:
        const css_selector::vector& selectors() const
        {
            return m_selectors;
        }

        template <class Input>
        void parse_css_stylesheet(const Input& input, const std::string& baseurl, const std::shared_ptr<document>& doc,
                                  const media_query_list_list::ptr& media = nullptr, bool top_level = true);

        void sort_selectors();

      private:
        bool parse_style_rule(const raw_rule::ptr& rule, const std::string& baseurl,
                              const std::shared_ptr<document>& doc, const media_query_list_list::ptr& media);
        void parse_import_rule(const raw_rule::ptr& rule, const std::string& baseurl,
                               const std::shared_ptr<document>& doc, const media_query_list_list::ptr& media);
        void add_selector(const css_selector::ptr& selector);

        int m_layer_depth = 0;
    };

    inline void css::add_selector(const css_selector::ptr& selector)
    {
        selector->m_order = static_cast<int>(m_selectors.size());
        selector->m_layered = m_layer_depth > 0;
        m_selectors.push_back(selector);
    }

}

#endif

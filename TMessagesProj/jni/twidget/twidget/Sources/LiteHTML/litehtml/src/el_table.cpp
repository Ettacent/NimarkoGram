/* Modifications Copyright (C) 2026 Ettacent */

#include "el_table.h"
#include "document.h"

namespace litehtml
{

    el_table::el_table(const std::shared_ptr<document>& doc) :
        html_tag(doc)
    {
    }

    bool el_table::appendChild(const element::ptr& el)
    {
        if(!el)
        {
            return false;
        }
        if(el->tag() == _tbody_ || el->tag() == _thead_ || el->tag() == _tfoot_ || el->tag() == _caption_)
        {
            return html_tag::appendChild(el);
        }
        return false;
    }

    void el_table::parse_attributes()
    {
        const char* str = get_attr("width");
        if(str)
        {
            map_to_dimension_property_ignoring_zero(_width_, str);
        }

        str = get_attr("height");
        if(str)
        {
            map_to_dimension_property(_height_, str);
        }

        str = get_attr("cellspacing");
        if(str)
        {
            map_to_pixel_length_property(_border_spacing_, str);
        }

        str = get_attr("border");
        if(str)
        {
            map_to_pixel_length_property_with_default_value(_border_width_, str, 1);
        }

        str = get_attr("bgcolor");
        if(str)
        {
            m_style.add_property(_background_color_, str, "", false, get_document()->container());
        }

        html_tag::parse_attributes();
    }

}

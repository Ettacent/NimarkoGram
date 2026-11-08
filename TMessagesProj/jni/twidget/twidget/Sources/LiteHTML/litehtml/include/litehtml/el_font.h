/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_EL_FONT_H
#define LITEHTML_EL_FONT_H

#include "html_tag.h"

namespace litehtml
{
    class el_font : public html_tag
    {
      public:
        explicit el_font(const std::shared_ptr<litehtml::document>& doc);

        void parse_attributes() override;
    };
}

#endif

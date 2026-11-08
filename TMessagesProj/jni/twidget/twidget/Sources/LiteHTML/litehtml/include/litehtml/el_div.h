/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_EL_DIV_H
#define LITEHTML_EL_DIV_H

#include "html_tag.h"

namespace litehtml
{
    class el_div : public html_tag
    {
      public:
        explicit el_div(const std::shared_ptr<litehtml::document>& doc);

        void parse_attributes() override;
    };
}

#endif

/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_EL_BODY_H
#define LITEHTML_EL_BODY_H

#include "html_tag.h"

namespace litehtml
{
    class el_body : public html_tag
    {
      public:
        explicit el_body(const std::shared_ptr<litehtml::document>& doc);

        bool is_body() const override;
    };
}

#endif

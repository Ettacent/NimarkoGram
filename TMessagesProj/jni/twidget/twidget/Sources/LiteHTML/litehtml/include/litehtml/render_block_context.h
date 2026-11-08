/* Modifications Copyright (C) 2026 Ettacent */

#ifndef LITEHTML_RENDER_BLOCK_CONTEXT_H
#define LITEHTML_RENDER_BLOCK_CONTEXT_H

#include "render_block.h"

namespace litehtml
{
    class render_item_block_context : public render_item_block
    {
      protected:
        pixel_t        m_va_applied = 0_px;
        rendered_width _render_content(pixel_t x, pixel_t y, bool second_pass,
                                       const containing_block_context& self_size, formatting_context* fmt_ctx) override;

      public:
        explicit render_item_block_context(std::shared_ptr<element> src_el) :
            render_item_block(std::move(src_el))
        {
        }

        std::shared_ptr<render_item> clone() override
        {
            return std::make_shared<render_item_block_context>(src_el());
        }
        pixel_t get_first_baseline() override;
        pixel_t get_last_baseline() override;
        void    apply_vertical_align() override;
    };
}

#endif

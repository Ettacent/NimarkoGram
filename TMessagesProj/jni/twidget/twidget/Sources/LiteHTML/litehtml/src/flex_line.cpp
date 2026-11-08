/* Modifications Copyright (C) 2026 Ettacent */

#include "flex_line.h"
#include "flex_item.h"
#include "types.h"

void litehtml::flex_line::distribute_free_space(pixel_t container_main_size)
{

    if(main_size - gaps() < container_main_size)
    {
        distribute_free_space_grow(container_main_size);
    } else
    {
        distribute_free_space_shrink(container_main_size);
    }
}

void litehtml::flex_line::distribute_free_space_grow(pixel_t container_main_size)
{
    pixel_t initial_free_space = container_main_size;

    bool all_inflexible = true;

    for(auto& item : items)
    {


        if(item->grow == 0 || item->base_size > item->main_size)
        {
            item->frozen        = true;
            item->clamp_state   = flex_clamp_state_inflexible;
            initial_free_space -= item->main_size;
        } else
        {
            initial_free_space -= item->base_size;
            all_inflexible      = false;
        }
    }



    if(all_inflexible)
    {
        return;
    }

    while(true)
    {

        int     sum_flex_grow_factor = 0;
        pixel_t remaining_free_space = container_main_size;

        for(auto& item : items)
        {
            if(item->frozen)
            {
                remaining_free_space -= item->main_size;
            } else
            {
                remaining_free_space -= item->base_size;
                sum_flex_grow_factor += item->grow;
            }
        }

        if(sum_flex_grow_factor < 1000)
        {
            pixel_t adjusted_free_space = initial_free_space * pixel_t(sum_flex_grow_factor) / 1000_px;
            if(adjusted_free_space < remaining_free_space)
            {
                remaining_free_space = adjusted_free_space;
            }
        }



        if(remaining_free_space != 0_px)
        {
            for(auto& item : items)
            {
                if(!item->frozen)
                {

                    item->main_size =
                        item->base_size + remaining_free_space * pixel_t(item->grow) / pixel_t(sum_flex_grow_factor);
                }
            }
        }

        if(fix_min_max_violations())
        {
            break;
        }
    }
}

void litehtml::flex_line::distribute_free_space_shrink(pixel_t container_main_size)
{
    pixel_t initial_free_space = container_main_size;

    bool all_inflexible = true;

    for(auto& item : items)
    {


        if(item->shrink == 0 || item->base_size < item->main_size)
        {
            item->frozen        = true;
            item->clamp_state   = flex_clamp_state_inflexible;
            initial_free_space -= item->main_size;
        } else
        {
            initial_free_space -= item->base_size;
            all_inflexible      = false;
        }
    }



    if(all_inflexible)
    {
        return;
    }

    while(true)
    {

        int     sum_flex_shrink_factor        = 0;
        pixel_t sum_scaled_flex_shrink_factor = 0_px;
        pixel_t remaining_free_space          = container_main_size;

        for(auto& item : items)
        {
            if(item->frozen)
            {
                remaining_free_space -= item->main_size;
            } else
            {
                remaining_free_space          -= item->base_size;
                sum_flex_shrink_factor        += item->shrink;
                sum_scaled_flex_shrink_factor += item->scaled_flex_shrink_factor;
            }
        }

        if(sum_flex_shrink_factor < 1000)
        {
            pixel_t adjusted_free_space = initial_free_space * pixel_t(sum_flex_shrink_factor) / 1000_px;
            if(adjusted_free_space > remaining_free_space)
            {
                remaining_free_space = adjusted_free_space;
            }
        }



        if(remaining_free_space != 0_px)
        {
            for(auto& item : items)
            {
                if(!item->frozen)
                {

                    item->main_size = sum_scaled_flex_shrink_factor > 0_px
                                          ? item->base_size + remaining_free_space * item->scaled_flex_shrink_factor /
                                                                  sum_scaled_flex_shrink_factor
                                          : item->base_size;
                }
            }
        }

        if(fix_min_max_violations())
        {
            break;
        }
    }
}

bool litehtml::flex_line::fix_min_max_violations()
{

    pixel_t total_violation = 0_px;

    for(auto& item : items)
    {
        if(!item->frozen)
        {
            if(item->main_size < item->min_size)
            {
                total_violation   += item->min_size - item->main_size;
                item->main_size    = item->min_size;
                item->clamp_state  = flex_clamp_state_min_violation;
            } else if(!item->max_size.is_default() && item->main_size > item->max_size)
            {
                total_violation   += pixel_t(item->max_size) - item->main_size;
                item->main_size    = item->max_size;
                item->clamp_state  = flex_clamp_state_max_violation;
            }
        }
    }


    if(total_violation == 0_px)
    {
        return true;
    }

    bool all_frozen = true;

    flex_clamp_state state_to_freeze =
        total_violation > 0_px ? flex_clamp_state_min_violation : flex_clamp_state_max_violation;

    for(auto& item : items)
    {
        if(!item->frozen)
        {
            if(item->clamp_state == state_to_freeze)
            {
                item->frozen = true;
            } else
            {
                all_frozen        = false;
                item->clamp_state = flex_clamp_state_unclamped;
            }
        }
    }

    return all_frozen;
}

bool litehtml::flex_line::distribute_main_auto_margins(pixel_t free_main_size)
{
    if(free_main_size > 0_px && (num_auto_margin_main_start || num_auto_margin_main_end))
    {
        pixel_t add = free_main_size / static_cast<int>(items.size() * 2);
        for(auto& item : items)
        {
            if(!item->auto_margin_main_start.is_default())
            {
                item->auto_margin_main_start  = add;
                item->main_size              += add;
                main_size                    += add;
                free_main_size               -= add;
            }
            if(!item->auto_margin_main_end.is_default())
            {
                item->auto_margin_main_end  = add;
                item->main_size            += add;
                main_size                  += add;
                free_main_size             -= add;
            }
        }

        pixel_t ditribute_step = 1_px;
        while(free_main_size > 0_px)
        {
            for(auto& item : items)
            {
                if(!item->auto_margin_main_start.is_default())
                {
                    item->auto_margin_main_start  = pixel_t(item->auto_margin_main_start) + ditribute_step;
                    free_main_size               -= ditribute_step;
                    if(free_main_size < ditribute_step)
                    {
                        break;
                    }
                }
                if(!item->auto_margin_main_end.is_default())
                {
                    item->auto_margin_main_end  = pixel_t(item->auto_margin_main_end) + ditribute_step;
                    free_main_size             -= ditribute_step;
                    if(free_main_size < ditribute_step)
                    {
                        break;
                    }
                }
            }
        }
        return true;
    }
    return false;
}

void litehtml::flex_line::init(pixel_t container_main_size, bool fit_container, bool is_row_direction,
                               const litehtml::containing_block_context& self_size,
                               litehtml::formatting_context*             fmt_ctx)
{
    if(!fit_container)
    {
        distribute_free_space(container_main_size - gaps());
    }

    cross_size = 0;
    main_size  = 0;
    first_baseline.set(0_px, baseline::baseline_type_none);
    last_baseline.set(0_px, baseline::baseline_type_none);

    if(is_row_direction)
    {
        def_value<pixel_t> first_baseline_top    = 0_px;
        def_value<pixel_t> first_baseline_bottom = 0_px;
        def_value<pixel_t> last_baseline_top     = 0_px;
        def_value<pixel_t> last_baseline_bottom  = 0_px;
        pixel_t            non_baseline_height   = 0_px;

        def_value<pixel_t> max_cross_size(0_px);
        if(self_size.height.type != containing_block_context::cbc_value_type_auto)
        {
            max_cross_size = self_size.height;
        }
        if(self_size.max_height.type != containing_block_context::cbc_value_type_none)
        {
            if(max_cross_size.is_default())
            {
                max_cross_size = self_size.max_height;
            } else
            {
                max_cross_size = std::max(pixel_t(max_cross_size), pixel_t(self_size.max_height));
            }
        }

        for(auto& item : items)
        {
            item->el->render(0_px, 0_px,
                             self_size.new_width(item->main_size - item->el->render_offset_width(),
                                                 containing_block_context::size_mode_exact_width),
                             fmt_ctx, false);

            if((item->align & 0xFF) == flex_align_items_baseline)
            {
                if(item->align & flex_align_items_last)
                {
                    last_baseline.type(reverse_cross ? baseline::baseline_type_top : baseline::baseline_type_bottom);

                    pixel_t top    = -item->el->get_last_baseline();
                    pixel_t bottom = top + item->el->height();

                    if(last_baseline_top.is_default())
                    {
                        last_baseline_top = top;
                    } else
                    {
                        last_baseline_top = std::min(pixel_t(last_baseline_top), top);
                    }

                    if(last_baseline_bottom.is_default())
                    {
                        last_baseline_bottom = bottom;
                    } else
                    {
                        last_baseline_bottom = std::max(pixel_t(last_baseline_bottom), bottom);
                    }
                } else
                {
                    first_baseline.type(reverse_cross ? baseline::baseline_type_bottom : baseline::baseline_type_top);
                    pixel_t top    = -item->el->get_first_baseline();
                    pixel_t bottom = top + item->el->height();

                    if(first_baseline_top.is_default())
                    {
                        first_baseline_top = top;
                    } else
                    {
                        first_baseline_top = std::min(pixel_t(first_baseline_top), top);
                    }

                    if(first_baseline_bottom.is_default())
                    {
                        first_baseline_bottom = bottom;
                    } else
                    {
                        first_baseline_bottom = std::max(pixel_t(first_baseline_bottom), bottom);
                    }
                }
            } else
            {
                non_baseline_height = std::max(non_baseline_height, item->el->height());
            }
            main_size += item->el->width();
        }

        main_size += gaps();
        cross_size = std::max(first_baseline_bottom.value() - first_baseline_top.value(),
                              last_baseline_bottom.value() - last_baseline_top.value());
        cross_size = std::max(cross_size, non_baseline_height);
        if(!max_cross_size.is_default() && cross_size > max_cross_size)
        {
            cross_size = max_cross_size;
        }

        first_baseline.calc(first_baseline_top, first_baseline_bottom);
        last_baseline.calc(last_baseline_top, last_baseline_bottom);
    } else
    {
        def_value<pixel_t> max_cross_size(0_px);
        if(self_size.width.type != containing_block_context::cbc_value_type_auto)
        {
            max_cross_size = self_size.width;
        }
        if(self_size.max_width.type != containing_block_context::cbc_value_type_none)
        {
            if(max_cross_size.is_default())
            {
                max_cross_size = self_size.max_width;
            } else
            {
                max_cross_size = std::max(pixel_t(max_cross_size), pixel_t(self_size.max_width));
            }
        }

        for(auto& item : items)
        {
            pixel_t el_ret_width = item->el->render(0_px, 0_px, self_size, fmt_ctx, false).natural_width;
            const auto& css = item->el->css();
            if(!css.get_width().is_predefined())
                el_ret_width = item->el->width();
            if(!css.get_max_width().is_predefined())
                el_ret_width = std::min(el_ret_width, css.get_max_width().calc_percent(self_size.render_width) + item->el->render_offset_width());
            if(!css.get_min_width().is_predefined())
                el_ret_width = std::max(el_ret_width, css.get_min_width().calc_percent(self_size.render_width) + item->el->render_offset_width());
            item->el->render(0_px, 0_px,
                             self_size.new_width_height(el_ret_width - item->el->render_offset_width(),
                                                        item->main_size - item->el->render_offset_height(),
                                                        containing_block_context::size_mode_exact_width |
                                                            containing_block_context::size_mode_exact_height |
                                                            containing_block_context::size_mode_resolved_width),
                             fmt_ctx, false);
            main_size  += item->el->height();
            cross_size  = std::max(cross_size, item->el->width());
        }
        main_size += gaps();
        if(!max_cross_size.is_default() && cross_size > max_cross_size)
        {
            cross_size = max_cross_size;
        }
    }
}

litehtml::pixel_t litehtml::flex_line::calculate_items_position(pixel_t                         container_main_size,
                                                                flex_justify_content            justify_content,
                                                                bool                            is_row_direction,
                                                                const containing_block_context& self_size,
                                                                formatting_context*             fmt_ctx)
{
    pixel_t free_main_size = container_main_size - main_size;
    distribute_main_auto_margins(free_main_size);
    free_main_size = container_main_size - main_size;

    switch(justify_content)
    {
    case flex_justify_content_left:
    case flex_justify_content_right:
        if(!is_row_direction)
        {
            justify_content = flex_justify_content_start;
        }
        break;
    case flex_justify_content_space_between:
        if(items.size() == 1 || free_main_size < 0_px)
        {
            justify_content = flex_justify_content_flex_start;
        }
        break;
    case flex_justify_content_space_around:
    case flex_justify_content_space_evenly:
        if(items.size() == 1 || free_main_size < 0_px)
        {
            justify_content = flex_justify_content_center;
        }
        break;
    default:
        break;
    }

    pixel_t main_pos        = 0_px;
    pixel_t add_before_item = 0_px;
    pixel_t add_after_item  = 0_px;
    pixel_t item_remainder  = 0_px;

    switch(justify_content)
    {

    case flex_justify_content_right:
        main_pos = free_main_size;
        break;
    case flex_justify_content_left:
    case flex_justify_content_start:
        main_pos = 0_px;
        break;
    case flex_justify_content_end:
        main_pos = free_main_size;
        break;
    case flex_justify_content_flex_end:
        if(!reverse_main)
        {
            main_pos = free_main_size;
        }
        break;
    case flex_justify_content_center:
        main_pos = free_main_size / 2_px;
        break;
    case flex_justify_content_space_between:
        add_after_item = free_main_size / static_cast<int>(items.size() - 1);
        item_remainder = free_main_size - (add_after_item * static_cast<int>(items.size() - 1));
        break;
    case flex_justify_content_space_around:
        add_after_item = add_before_item = free_main_size / static_cast<int>(items.size() * 2);
        item_remainder                   = free_main_size - (add_after_item * static_cast<int>(items.size() * 2));
        break;
    case flex_justify_content_space_evenly:
        add_before_item = free_main_size / static_cast<int>(items.size() + 1);
        item_remainder  = free_main_size - add_before_item * static_cast<int>(items.size() + 1);
        break;
    default:
        if(reverse_main)
        {
            main_pos = free_main_size;
        }
        break;
    }

    pixel_t height = 0_px;

    pixel_t distribute_step = 1_px;
    for(auto& item : items)
    {
        main_pos += add_before_item;
        if(add_before_item > 0_px && item_remainder > 0_px)
        {
            main_pos       += distribute_step;
            item_remainder -= distribute_step;
        }
        item->place(*this, main_pos, self_size, fmt_ctx);
        main_pos += item->get_el_main_size() + add_after_item + main_gap;
        if(add_after_item > 0_px && item_remainder > 0_px)
        {
            main_pos       += distribute_step;
            item_remainder -= distribute_step;
        }
        height = std::max(height, item->el->bottom());
    }
    return height;
}

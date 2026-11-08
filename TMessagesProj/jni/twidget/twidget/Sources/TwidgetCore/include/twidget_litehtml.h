/* Modifications Copyright (C) 2026 Ettacent */

#ifndef TWIDGET_LITEHTML_H
#define TWIDGET_LITEHTML_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct twl_renderer twl_renderer;

twl_renderer *twl_create(double width, double dpr, const char *theme);
void twl_destroy(twl_renderer *handle);
char *twl_receive(twl_renderer *handle, const char *json);
char *twl_set_width(twl_renderer *handle, double width);
char *twl_set_max_height(twl_renderer *handle, double height);
char *twl_set_theme(twl_renderer *handle, const char *theme);
char *twl_touch(twl_renderer *handle, int32_t action, double x, double y, int32_t pointer, bool primary);
char *twl_pointer(twl_renderer *handle, int32_t action, double x, double y, int32_t pointer, bool primary, bool mouse, int32_t modifiers, int32_t button);
char *twl_hover(twl_renderer *handle, int32_t action, double x, double y, int32_t modifiers);
char *twl_wheel(twl_renderer *handle, double x, double y, double dx, double dy, int32_t mode, int32_t modifiers);
char *twl_key(twl_renderer *handle, const char *type, const char *key, const char *code, bool repeat, int32_t modifiers);
char *twl_editor(twl_renderer *handle);
char *twl_edit(twl_renderer *handle, uint64_t generation, const char *text, const char *input_type);
char *twl_end_editing(twl_renderer *handle);
char *twl_selected_text(twl_renderer *handle);
bool twl_clear_selection(twl_renderer *handle);
char *twl_selectable_text(twl_renderer *handle);
char *twl_cursor(twl_renderer *handle);
char *twl_master_css(twl_renderer *handle);
char *twl_theme_variables(twl_renderer *handle);
void twl_set_resize_tracking(twl_renderer *handle, bool enabled, bool prefers_bottom);
char *twl_tick(twl_renderer *handle);
bool twl_wants_tick(twl_renderer *handle);
double twl_next_tick_in(twl_renderer *handle);
void twl_set_compositing(twl_renderer *handle, bool enabled);
char *twl_composition(twl_renderer *handle);
double twl_clock(void);
char *twl_layer_content(twl_renderer *handle, uint32_t index);
void twl_size(twl_renderer *handle, uint32_t *width, uint32_t *height);
bool twl_dirty(twl_renderer *handle);
double twl_render(twl_renderer *handle, uint8_t *buffer, size_t capacity);
char *twl_image_ready(twl_renderer *handle, int32_t image, float width, float height);
char *twl_display_list(twl_renderer *handle);
char *twl_damage(twl_renderer *handle);
void twl_trim(twl_renderer *handle);
char *twl_stats(twl_renderer *handle);
char *twl_html(twl_renderer *handle);
void twl_string_free(char *s);

#ifdef __cplusplus
}
#endif
#endif

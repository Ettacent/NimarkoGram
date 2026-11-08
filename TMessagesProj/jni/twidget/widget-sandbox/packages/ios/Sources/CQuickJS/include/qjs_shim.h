/* Modifications Copyright (C) 2026 Ettacent */

#ifndef QJS_SHIM_H
#define QJS_SHIM_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "../quickjs/quickjs.h"

#ifdef __cplusplus
extern "C" {
#endif

JSValue qjs_undefined(void);
JSValue qjs_null(void);
bool qjs_is_exception(JSValue v);
bool qjs_is_undefined(JSValue v);
bool qjs_is_string(JSValue v);
bool qjs_is_number(JSValue v);
bool qjs_is_function(JSContext *ctx, JSValue v);
void qjs_free_value(JSContext *ctx, JSValue v);
JSValue qjs_dup_value(JSContext *ctx, JSValue v);

JSValue qjs_new_string(JSContext *ctx, const char *s);
JSValue qjs_new_string_len(JSContext *ctx, const char *s, size_t len);
JSValue qjs_new_float64(JSContext *ctx, double d);
JSValue qjs_new_array_buffer_copy(JSContext *ctx, const uint8_t *buf, size_t len);
JSValue qjs_throw_error(JSContext *ctx, const char *message);

JSValue qjs_new_cfunction_magic(JSContext *ctx, JSCFunctionMagic *fn, const char *name, int length, int magic);
int qjs_typed_array_bytes(JSContext *ctx, JSValue v, uint8_t **ptr, size_t *len);

const char *qjs_to_cstring(JSContext *ctx, JSValue v);
void qjs_free_cstring(JSContext *ctx, const char *s);
double qjs_to_float64(JSContext *ctx, JSValue v);

JSValue qjs_eval(JSContext *ctx, const char *code, size_t len, const char *filename, bool as_module);

uint8_t *qjs_compile_bytecode(JSContext *ctx, const char *code, size_t len, const char *filename, size_t *out_len);
void qjs_free_bytecode(JSContext *ctx, uint8_t *blob);
JSValue qjs_eval_bytecode(JSContext *ctx, const uint8_t *blob, size_t blob_len, const char *source, size_t source_len);
JSValue qjs_call(JSContext *ctx, JSValue fn, int argc, JSValue *argv);
int qjs_set_global_prop(JSContext *ctx, const char *name, JSValue v);
JSValue qjs_get_global_prop(JSContext *ctx, const char *name);

char *qjs_take_exception_description(JSContext *ctx);

bool qjs_is_job_pending(JSRuntime *rt);
int qjs_execute_pending_job(JSRuntime *rt, JSContext **pctx);
size_t qjs_memory_used(JSRuntime *rt);

#ifdef __cplusplus
}
#endif
#endif

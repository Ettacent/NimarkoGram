/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");
#ifndef GUMBO_STRING_BUFFER_H_
#define GUMBO_STRING_BUFFER_H_

#include <stdbool.h>
#include <stddef.h>

#include "gumbo.h"

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;

typedef struct {
  char* data;

  size_t length;

  size_t capacity;
} GumboStringBuffer;

void gumbo_string_buffer_init(
    struct GumboInternalParser* parser, GumboStringBuffer* output);

void gumbo_string_buffer_reserve(struct GumboInternalParser* parser,
    size_t min_capacity, GumboStringBuffer* output);

void gumbo_string_buffer_append_codepoint(
    struct GumboInternalParser* parser, int c, GumboStringBuffer* output);

void gumbo_string_buffer_append_string(struct GumboInternalParser* parser,
    GumboStringPiece* str, GumboStringBuffer* output);

char* gumbo_string_buffer_to_string(
    struct GumboInternalParser* parser, GumboStringBuffer* input);

void gumbo_string_buffer_clear(
    struct GumboInternalParser* parser, GumboStringBuffer* input);

void gumbo_string_buffer_destroy(
    struct GumboInternalParser* parser, GumboStringBuffer* buffer);

#ifdef __cplusplus
}
#endif

#endif

/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_UTF8_H_
#define GUMBO_UTF8_H_

#include <stdbool.h>
#include <stddef.h>

#include "gumbo.h"

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalError;
struct GumboInternalParser;

extern const int kUtf8ReplacementChar;

typedef struct GumboInternalUtf8Iterator {
  const char* _start;

  const char* _mark;

  const char* _end;

  int _current;

  int _width;

  GumboSourcePosition _pos;

  GumboSourcePosition _mark_pos;

  struct GumboInternalParser* _parser;
} Utf8Iterator;

bool utf8_is_invalid_code_point(int c);

void utf8iterator_init(struct GumboInternalParser* parser, const char* source,
    size_t source_length, Utf8Iterator* iter);

void utf8iterator_next(Utf8Iterator* iter);

int utf8iterator_current(const Utf8Iterator* iter);

void utf8iterator_get_position(
    const Utf8Iterator* iter, GumboSourcePosition* output);

const char* utf8iterator_get_char_pointer(const Utf8Iterator* iter);

const char* utf8iterator_get_end_pointer(const Utf8Iterator* iter);

bool utf8iterator_maybe_consume_match(
    Utf8Iterator* iter, const char* prefix, size_t length, bool case_sensitive);

void utf8iterator_mark(Utf8Iterator* iter);

void utf8iterator_reset(Utf8Iterator* iter);

void utf8iterator_fill_error_at_mark(
    Utf8Iterator* iter, struct GumboInternalError* error);

#ifdef __cplusplus
}
#endif
#endif

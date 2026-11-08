/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#include "utf8.h"

#include <assert.h>
#include <stdint.h>
#include <string.h>
#include <strings.h>

#include "error.h"
#include "gumbo.h"
#include "parser.h"
#include "util.h"
#include "vector.h"

const int kUtf8ReplacementChar = 0xFFFD;


// Copyright (c) 2008-2009 Bjoern Hoehrmann <bjoern@hoehrmann.de>

// Permission is hereby granted, free of charge, to any person obtaining a copy

// The above copyright notice and this permission notice shall be included in

#define UTF8_ACCEPT 0
#define UTF8_REJECT 12

static const uint8_t utf8d[] = {
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9,
    9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 10,
    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8,
    8, 8, 8, 8, 8, 8,

    0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12,
    12, 12, 12, 12, 24, 12, 24, 12, 12, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12,
    12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12,
    12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12,
    12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
};

uint32_t static inline decode(uint32_t* state, uint32_t* codep, uint32_t byte) {
  uint32_t type = utf8d[byte];

  *codep = (*state != UTF8_ACCEPT) ? (byte & 0x3fu) | (*codep << 6)
                                   : (0xff >> type) & (byte);

  *state = utf8d[256 + *state + type];
  return *state;
}


static void add_error(Utf8Iterator* iter, GumboErrorType type) {
  GumboParser* parser = iter->_parser;

  GumboError* error = gumbo_add_error(parser);
  if (!error) {
    return;
  }
  error->type = type;
  error->position = iter->_pos;
  error->original_text = iter->_start;

  uint64_t code_point = 0;
  for (int i = 0; i < iter->_width; ++i) {
    code_point = (code_point << 8) | (unsigned char) iter->_start[i];
  }
  error->v.codepoint = code_point;
}

static void read_char(Utf8Iterator* iter) {
  if (iter->_start >= iter->_end) {
    iter->_current = -1;
    iter->_width = 0;
    return;
  }

  uint32_t code_point = 0;
  uint32_t state = UTF8_ACCEPT;
  for (const char* c = iter->_start; c < iter->_end; ++c) {
    decode(&state, &code_point, (uint32_t)(unsigned char) (*c));
    if (state == UTF8_ACCEPT) {
      iter->_width = c - iter->_start + 1;
      if (code_point == '\r') {
        assert(iter->_width == 1);
        const char* next = c + 1;
        if (next < iter->_end && *next == '\n') {
          ++iter->_start;
          ++iter->_pos.offset;
        }
        code_point = '\n';
      }
      if (utf8_is_invalid_code_point(code_point)) {
        add_error(iter, GUMBO_ERR_UTF8_INVALID);
        code_point = kUtf8ReplacementChar;
      }
      iter->_current = code_point;
      return;
    } else if (state == UTF8_REJECT) {
      iter->_width = c - iter->_start + (c == iter->_start);
      iter->_current = kUtf8ReplacementChar;
      add_error(iter, GUMBO_ERR_UTF8_INVALID);
      return;
    }
  }
  iter->_current = kUtf8ReplacementChar;
  iter->_width = iter->_end - iter->_start;
  add_error(iter, GUMBO_ERR_UTF8_TRUNCATED);
}

static void update_position(Utf8Iterator* iter) {
  iter->_pos.offset += iter->_width;
  if (iter->_current == '\n') {
    ++iter->_pos.line;
    iter->_pos.column = 1;
  } else if (iter->_current == '\t') {
    int tab_stop = iter->_parser->_options->tab_stop;
    iter->_pos.column = ((iter->_pos.column / tab_stop) + 1) * tab_stop;
  } else if (iter->_current != -1) {
    ++iter->_pos.column;
  }
}

bool utf8_is_invalid_code_point(int c) {
  return (c >= 0x1 && c <= 0x8) || c == 0xB || (c >= 0xE && c <= 0x1F) ||
         (c >= 0x7F && c <= 0x9F) || (c >= 0xFDD0 && c <= 0xFDEF) ||
         ((c & 0xFFFF) == 0xFFFE) || ((c & 0xFFFF) == 0xFFFF);
}

void utf8iterator_init(GumboParser* parser, const char* source,
    size_t source_length, Utf8Iterator* iter) {
  iter->_start = source;
  iter->_end = source + source_length;
  iter->_pos.line = 1;
  iter->_pos.column = 1;
  iter->_pos.offset = 0;
  iter->_parser = parser;
  read_char(iter);
}

void utf8iterator_next(Utf8Iterator* iter) {
  update_position(iter);
  iter->_start += iter->_width;
  read_char(iter);
}

int utf8iterator_current(const Utf8Iterator* iter) { return iter->_current; }

void utf8iterator_get_position(
    const Utf8Iterator* iter, GumboSourcePosition* output) {
  *output = iter->_pos;
}

const char* utf8iterator_get_char_pointer(const Utf8Iterator* iter) {
  return iter->_start;
}

const char* utf8iterator_get_end_pointer(const Utf8Iterator* iter) {
  return iter->_end;
}

bool utf8iterator_maybe_consume_match(Utf8Iterator* iter, const char* prefix,
    size_t length, bool case_sensitive) {
  bool matched = (iter->_start + length <= iter->_end) &&
                 (case_sensitive ? !strncmp(iter->_start, prefix, length)
                                 : !strncasecmp(iter->_start, prefix, length));
  if (matched) {
    for (unsigned int i = 0; i < length; ++i) {
      utf8iterator_next(iter);
    }
    return true;
  } else {
    return false;
  }
}

void utf8iterator_mark(Utf8Iterator* iter) {
  iter->_mark = iter->_start;
  iter->_mark_pos = iter->_pos;
}

void utf8iterator_reset(Utf8Iterator* iter) {
  iter->_start = iter->_mark;
  iter->_pos = iter->_mark_pos;
  read_char(iter);
}

void utf8iterator_fill_error_at_mark(Utf8Iterator* iter, GumboError* error) {
  error->position = iter->_mark_pos;
  error->original_text = iter->_mark;
}

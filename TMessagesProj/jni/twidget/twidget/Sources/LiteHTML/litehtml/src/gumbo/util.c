/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#include "util.h"

#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdarg.h>
#include <stdio.h>

#include "gumbo.h"
#include "parser.h"

const GumboSourcePosition kGumboEmptySourcePosition = {0, 0, 0};

void* gumbo_parser_allocate(GumboParser* parser, size_t num_bytes) {
  return parser->_options->allocator(parser->_options->userdata, num_bytes);
}

void gumbo_parser_deallocate(GumboParser* parser, void* ptr) {
  parser->_options->deallocator(parser->_options->userdata, ptr);
}

char* gumbo_copy_stringz(GumboParser* parser, const char* str) {
  char* buffer = gumbo_parser_allocate(parser, strlen(str) + 1);
  strcpy(buffer, str);
  return buffer;
}

void gumbo_debug(const char* format, ...) {
#ifdef GUMBO_DEBUG
  va_list args;
  va_start(args, format);
  vprintf(format, args);
  va_end(args);
  fflush(stdout);
#endif
}

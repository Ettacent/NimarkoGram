/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_UTIL_H_
#define GUMBO_UTIL_H_
#ifdef _MSC_VER
#define _CRT_SECURE_NO_WARNINGS
#endif
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;

char* gumbo_copy_stringz(struct GumboInternalParser* parser, const char* str);

void* gumbo_parser_allocate(
    struct GumboInternalParser* parser, size_t num_bytes);

void gumbo_parser_deallocate(struct GumboInternalParser* parser, void* ptr);

void gumbo_debug(const char* format, ...);

#ifdef __cplusplus
}
#endif

#endif

/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2011 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_CHAR_REF_H_
#define GUMBO_CHAR_REF_H_

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;
struct GumboInternalUtf8Iterator;

extern const int kGumboNoChar;

typedef struct {
  int first;
  int second;
} OneOrTwoCodepoints;

bool consume_char_ref(struct GumboInternalParser* parser,
    struct GumboInternalUtf8Iterator* input, int additional_allowed_char,
    bool is_in_attribute, OneOrTwoCodepoints* output);

#ifdef __cplusplus
}
#endif

#endif

/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_TOKENIZER_H_
#define GUMBO_TOKENIZER_H_

#include <stdbool.h>
#include <stddef.h>

#include "gumbo.h"
#include "token_type.h"
#include "tokenizer_states.h"

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;

typedef struct GumboInternalTokenDocType {
  const char* name;
  const char* public_identifier;
  const char* system_identifier;
  bool force_quirks;
  bool has_public_identifier;
  bool has_system_identifier;
} GumboTokenDocType;

typedef struct GumboInternalTokenStartTag {
  GumboTag tag;
  GumboVector   attributes;
  bool is_self_closing;
} GumboTokenStartTag;

typedef struct GumboInternalToken {
  GumboTokenType type;
  GumboSourcePosition position;
  GumboStringPiece original_text;
  union {
    GumboTokenDocType doc_type;
    GumboTokenStartTag start_tag;
    GumboTag end_tag;
    const char* text;
    int character;
  } v;
} GumboToken;

void gumbo_tokenizer_state_init(
    struct GumboInternalParser* parser, const char* text, size_t text_length);

void gumbo_tokenizer_state_destroy(struct GumboInternalParser* parser);

void gumbo_tokenizer_set_state(
    struct GumboInternalParser* parser, GumboTokenizerEnum state);

void gumbo_tokenizer_set_is_current_node_foreign(
    struct GumboInternalParser* parser, bool is_foreign);

bool gumbo_lex(struct GumboInternalParser* parser, GumboToken* output);

void gumbo_token_destroy(struct GumboInternalParser* parser, GumboToken* token);

#ifdef __cplusplus
}
#endif

#endif

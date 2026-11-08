/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_ERROR_H_
#define GUMBO_ERROR_H_
#ifdef _MSC_VER
#define _CRT_SECURE_NO_WARNINGS
#endif
#include <stdint.h>

#include "gumbo.h"
#include "insertion_mode.h"
#include "string_buffer.h"
#include "token_type.h"

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;

typedef enum {
  GUMBO_ERR_UTF8_INVALID,
  GUMBO_ERR_UTF8_TRUNCATED,
  GUMBO_ERR_UTF8_NULL,
  GUMBO_ERR_NUMERIC_CHAR_REF_NO_DIGITS,
  GUMBO_ERR_NUMERIC_CHAR_REF_WITHOUT_SEMICOLON,
  GUMBO_ERR_NUMERIC_CHAR_REF_INVALID,
  GUMBO_ERR_NAMED_CHAR_REF_WITHOUT_SEMICOLON,
  GUMBO_ERR_NAMED_CHAR_REF_INVALID,
  GUMBO_ERR_TAG_STARTS_WITH_QUESTION,
  GUMBO_ERR_TAG_EOF,
  GUMBO_ERR_TAG_INVALID,
  GUMBO_ERR_CLOSE_TAG_EMPTY,
  GUMBO_ERR_CLOSE_TAG_EOF,
  GUMBO_ERR_CLOSE_TAG_INVALID,
  GUMBO_ERR_SCRIPT_EOF,
  GUMBO_ERR_ATTR_NAME_EOF,
  GUMBO_ERR_ATTR_NAME_INVALID,
  GUMBO_ERR_ATTR_DOUBLE_QUOTE_EOF,
  GUMBO_ERR_ATTR_SINGLE_QUOTE_EOF,
  GUMBO_ERR_ATTR_UNQUOTED_EOF,
  GUMBO_ERR_ATTR_UNQUOTED_RIGHT_BRACKET,
  GUMBO_ERR_ATTR_UNQUOTED_EQUALS,
  GUMBO_ERR_ATTR_AFTER_EOF,
  GUMBO_ERR_ATTR_AFTER_INVALID,
  GUMBO_ERR_DUPLICATE_ATTR,
  GUMBO_ERR_SOLIDUS_EOF,
  GUMBO_ERR_SOLIDUS_INVALID,
  GUMBO_ERR_DASHES_OR_DOCTYPE,
  GUMBO_ERR_COMMENT_EOF,
  GUMBO_ERR_COMMENT_INVALID,
  GUMBO_ERR_COMMENT_BANG_AFTER_DOUBLE_DASH,
  GUMBO_ERR_COMMENT_DASH_AFTER_DOUBLE_DASH,
  GUMBO_ERR_COMMENT_SPACE_AFTER_DOUBLE_DASH,
  GUMBO_ERR_COMMENT_END_BANG_EOF,
  GUMBO_ERR_DOCTYPE_EOF,
  GUMBO_ERR_DOCTYPE_INVALID,
  GUMBO_ERR_DOCTYPE_SPACE,
  GUMBO_ERR_DOCTYPE_RIGHT_BRACKET,
  GUMBO_ERR_DOCTYPE_SPACE_OR_RIGHT_BRACKET,
  GUMBO_ERR_DOCTYPE_END,
  GUMBO_ERR_PARSER,
  GUMBO_ERR_UNACKNOWLEDGED_SELF_CLOSING_TAG,
} GumboErrorType;

typedef struct GumboInternalDuplicateAttrError {
  const char* name;

  unsigned int original_index;

  unsigned int new_index;
} GumboDuplicateAttrError;

typedef enum {
  GUMBO_ERR_TOKENIZER_DATA,
  GUMBO_ERR_TOKENIZER_CHAR_REF,
  GUMBO_ERR_TOKENIZER_RCDATA,
  GUMBO_ERR_TOKENIZER_RAWTEXT,
  GUMBO_ERR_TOKENIZER_PLAINTEXT,
  GUMBO_ERR_TOKENIZER_SCRIPT,
  GUMBO_ERR_TOKENIZER_TAG,
  GUMBO_ERR_TOKENIZER_SELF_CLOSING_TAG,
  GUMBO_ERR_TOKENIZER_ATTR_NAME,
  GUMBO_ERR_TOKENIZER_ATTR_VALUE,
  GUMBO_ERR_TOKENIZER_MARKUP_DECLARATION,
  GUMBO_ERR_TOKENIZER_COMMENT,
  GUMBO_ERR_TOKENIZER_DOCTYPE,
  GUMBO_ERR_TOKENIZER_CDATA,
} GumboTokenizerErrorState;

typedef struct GumboInternalTokenizerError {
  int codepoint;

  GumboTokenizerErrorState state;
} GumboTokenizerError;

typedef struct GumboInternalParserError {
  GumboTokenType input_type;

  GumboTag input_tag;

  GumboInsertionMode parser_state;

  GumboVector   tag_stack;
} GumboParserError;

typedef struct GumboInternalError {
  GumboErrorType type;

  GumboSourcePosition position;

  const char* original_text;

  union {
    uint64_t codepoint;

    GumboTokenizerError tokenizer;

    GumboStringPiece text;

    GumboDuplicateAttrError duplicate_attr;

    struct GumboInternalParserError parser;
  } v;
} GumboError;

GumboError* gumbo_add_error(struct GumboInternalParser* parser);

void gumbo_init_errors(struct GumboInternalParser* errors);

void gumbo_destroy_errors(struct GumboInternalParser* errors);

void gumbo_error_destroy(struct GumboInternalParser* parser, GumboError* error);

void gumbo_error_to_string(struct GumboInternalParser* parser,
    const GumboError* error, GumboStringBuffer* output);

void gumbo_caret_diagnostic_to_string(struct GumboInternalParser* parser,
    const GumboError* error, const char* source_text,
    GumboStringBuffer* output);

void gumbo_print_caret_diagnostic(struct GumboInternalParser* parser,
    const GumboError* error, const char* source_text);

#ifdef __cplusplus
}
#endif

#endif

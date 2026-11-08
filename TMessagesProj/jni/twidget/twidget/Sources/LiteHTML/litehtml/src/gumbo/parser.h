/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_PARSER_H_
#define GUMBO_PARSER_H_

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParserState;
struct GumboInternalOutput;
struct GumboInternalOptions;
struct GumboInternalTokenizerState;

typedef struct GumboInternalParser {
  const struct GumboInternalOptions* _options;

  struct GumboInternalOutput* _output;

  struct GumboInternalTokenizerState* _tokenizer_state;

  struct GumboInternalParserState* _parser_state;
} GumboParser;

#ifdef __cplusplus
}
#endif

#endif

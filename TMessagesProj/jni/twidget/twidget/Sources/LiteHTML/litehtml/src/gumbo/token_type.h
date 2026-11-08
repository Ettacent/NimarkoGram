/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2011 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_TOKEN_TYPE_H_
#define GUMBO_TOKEN_TYPE_H_

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  GUMBO_TOKEN_DOCTYPE,
  GUMBO_TOKEN_START_TAG,
  GUMBO_TOKEN_END_TAG,
  GUMBO_TOKEN_COMMENT,
  GUMBO_TOKEN_WHITESPACE,
  GUMBO_TOKEN_CHARACTER,
  GUMBO_TOKEN_CDATA,
  GUMBO_TOKEN_NULL,
  GUMBO_TOKEN_EOF
} GumboTokenType;

#ifdef __cplusplus
}
#endif

#endif

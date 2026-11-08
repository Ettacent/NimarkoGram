/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_ATTRIBUTE_H_
#define GUMBO_ATTRIBUTE_H_

#include "gumbo.h"

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;

void gumbo_destroy_attribute(
    struct GumboInternalParser* parser, GumboAttribute* attribute);

#ifdef __cplusplus
}
#endif

#endif

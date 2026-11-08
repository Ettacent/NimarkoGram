/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#ifndef GUMBO_VECTOR_H_
#define GUMBO_VECTOR_H_

#include "gumbo.h"

#ifdef __cplusplus
extern "C" {
#endif

struct GumboInternalParser;

void gumbo_vector_init(struct GumboInternalParser* parser,
    size_t initial_capacity, GumboVector* vector);

void gumbo_vector_destroy(
    struct GumboInternalParser* parser, GumboVector* vector);

void gumbo_vector_add(
    struct GumboInternalParser* parser, void* element, GumboVector* vector);

void* gumbo_vector_pop(struct GumboInternalParser* parser, GumboVector* vector);

void gumbo_vector_insert_at(struct GumboInternalParser* parser, void* element,
    unsigned int index, GumboVector* vector);

void gumbo_vector_remove(
    struct GumboInternalParser* parser, void* element, GumboVector* vector);

void* gumbo_vector_remove_at(struct GumboInternalParser* parser,
    unsigned int index, GumboVector* vector);

#ifdef __cplusplus
}
#endif

#endif

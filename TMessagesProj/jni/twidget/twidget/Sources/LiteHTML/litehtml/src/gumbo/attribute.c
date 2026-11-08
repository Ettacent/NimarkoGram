/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#include "attribute.h"

#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "util.h"

struct GumboInternalParser;

GumboAttribute* gumbo_get_attribute(
    const GumboVector* attributes, const char* name) {
  for (unsigned int i = 0; i < attributes->length; ++i) {
    GumboAttribute* attr = attributes->data[i];
    if (!strcasecmp(attr->name, name)) {
      return attr;
    }
  }
  return NULL;
}

void gumbo_destroy_attribute(
    struct GumboInternalParser* parser, GumboAttribute* attribute) {
  gumbo_parser_deallocate(parser, (void*) attribute->name);
  gumbo_parser_deallocate(parser, (void*) attribute->value);
  gumbo_parser_deallocate(parser, (void*) attribute);
}

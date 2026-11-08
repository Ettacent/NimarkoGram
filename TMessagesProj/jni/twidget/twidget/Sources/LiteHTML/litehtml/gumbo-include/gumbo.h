/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");


#ifndef GUMBO_GUMBO_H_
#define GUMBO_GUMBO_H_

#ifdef _MSC_VER
#define _CRT_SECURE_NO_WARNINGS
#define fileno _fileno
#endif

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
  unsigned int line;
  unsigned int column;
  unsigned int offset;
} GumboSourcePosition;

extern const GumboSourcePosition kGumboEmptySourcePosition;

typedef struct {
  const char* data;

  size_t length;
} GumboStringPiece;

extern const GumboStringPiece kGumboEmptyString;

bool gumbo_string_equals(
    const GumboStringPiece* str1, const GumboStringPiece* str2);

bool gumbo_string_equals_ignore_case(
    const GumboStringPiece* str1, const GumboStringPiece* str2);

typedef struct {
  void** data;

  unsigned int length;

  unsigned int capacity;
} GumboVector;

extern const GumboVector kGumboEmptyVector;

int gumbo_vector_index_of(GumboVector* vector, const void* element);

typedef enum {
#include "gumbo/tag_enum.h"
  GUMBO_TAG_UNKNOWN,
  GUMBO_TAG_LAST,
} GumboTag;

const char* gumbo_normalized_tagname(GumboTag tag);

void gumbo_tag_from_original_text(GumboStringPiece* text);

const char* gumbo_normalize_svg_tagname(const GumboStringPiece* tagname);

GumboTag gumbo_tag_enum(const char* tagname);
GumboTag gumbo_tagn_enum(const char* tagname, unsigned int length);

typedef enum {
  GUMBO_ATTR_NAMESPACE_NONE,
  GUMBO_ATTR_NAMESPACE_XLINK,
  GUMBO_ATTR_NAMESPACE_XML,
  GUMBO_ATTR_NAMESPACE_XMLNS,
} GumboAttributeNamespaceEnum;

typedef struct {
  GumboAttributeNamespaceEnum attr_namespace;

  const char* name;

  GumboStringPiece original_name;

  const char* value;

  GumboStringPiece original_value;

  GumboSourcePosition name_start;

  GumboSourcePosition name_end;

  GumboSourcePosition value_start;

  GumboSourcePosition value_end;
} GumboAttribute;

GumboAttribute* gumbo_get_attribute(const GumboVector* attrs, const char* name);

typedef enum {
  GUMBO_NODE_DOCUMENT,
  GUMBO_NODE_ELEMENT,
  GUMBO_NODE_TEXT,
  GUMBO_NODE_CDATA,
  GUMBO_NODE_COMMENT,
  GUMBO_NODE_WHITESPACE,
  GUMBO_NODE_TEMPLATE
} GumboNodeType;

typedef struct GumboInternalNode GumboNode;

typedef enum {
  GUMBO_DOCTYPE_NO_QUIRKS,
  GUMBO_DOCTYPE_QUIRKS,
  GUMBO_DOCTYPE_LIMITED_QUIRKS
} GumboQuirksModeEnum;

typedef enum {
  GUMBO_NAMESPACE_HTML,
  GUMBO_NAMESPACE_SVG,
  GUMBO_NAMESPACE_MATHML
} GumboNamespaceEnum;

typedef enum {
  GUMBO_INSERTION_NORMAL = 0,

  GUMBO_INSERTION_BY_PARSER = 1 << 0,

  GUMBO_INSERTION_IMPLICIT_END_TAG = 1 << 1,


  GUMBO_INSERTION_IMPLIED = 1 << 3,

  GUMBO_INSERTION_CONVERTED_FROM_END_TAG = 1 << 4,

  GUMBO_INSERTION_FROM_ISINDEX = 1 << 5,

  GUMBO_INSERTION_FROM_IMAGE = 1 << 6,

  GUMBO_INSERTION_RECONSTRUCTED_FORMATTING_ELEMENT = 1 << 7,

  GUMBO_INSERTION_ADOPTION_AGENCY_CLONED = 1 << 8,

  GUMBO_INSERTION_ADOPTION_AGENCY_MOVED = 1 << 9,

  GUMBO_INSERTION_FOSTER_PARENTED = 1 << 10,
} GumboParseFlags;

typedef struct {
  GumboVector   children;

  bool has_doctype;

  const char* name;
  const char* public_identifier;
  const char* system_identifier;

  GumboQuirksModeEnum doc_type_quirks_mode;
} GumboDocument;

typedef struct {
  const char* text;

  GumboStringPiece original_text;

  GumboSourcePosition start_pos;
} GumboText;

typedef struct {
  GumboVector   children;

  GumboTag tag;

  GumboNamespaceEnum tag_namespace;

  GumboStringPiece original_tag;

  GumboStringPiece original_end_tag;

  GumboSourcePosition start_pos;

  GumboSourcePosition end_pos;

  GumboVector   attributes;
} GumboElement;

struct GumboInternalNode {
  GumboNodeType type;

  GumboNode* parent;

  size_t index_within_parent;

  GumboParseFlags parse_flags;

  union {
    GumboDocument document;
    GumboElement element;
    GumboText text;
  } v;
};

typedef void* (*GumboAllocatorFunction)(void* userdata, size_t size);

typedef void (*GumboDeallocatorFunction)(void* userdata, void* ptr);

typedef struct GumboInternalOptions {
  GumboAllocatorFunction allocator;

  GumboDeallocatorFunction deallocator;

  void* userdata;

  int tab_stop;

  bool stop_on_first_error;

  int max_errors;

  GumboTag fragment_context;

  GumboNamespaceEnum fragment_namespace;
} GumboOptions;

extern const GumboOptions kGumboDefaultOptions;

typedef struct GumboInternalOutput {
  GumboNode* document;

  GumboNode* root;

  GumboVector   errors;
} GumboOutput;

GumboOutput* gumbo_parse(const char* buffer);

GumboOutput* gumbo_parse_with_options(
    const GumboOptions* options, const char* buffer, size_t buffer_length);

void gumbo_destroy_output(const GumboOptions* options, GumboOutput* output);

#ifdef __cplusplus
}
#endif

#endif

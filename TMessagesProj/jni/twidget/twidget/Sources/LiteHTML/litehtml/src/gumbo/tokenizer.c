/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#include "tokenizer.h"

#include <assert.h>
#include <stdbool.h>
#include <string.h>

#include "attribute.h"
#include "char_ref.h"
#include "error.h"
#include "gumbo.h"
#include "parser.h"
#include "string_buffer.h"
#include "string_piece.h"
#include "token_type.h"
#include "tokenizer_states.h"
#include "utf8.h"
#include "util.h"
#include "vector.h"

const GumboStringPiece kScriptTag = {"script", 6};

typedef enum {
  RETURN_ERROR,
  RETURN_SUCCESS,
  NEXT_CHAR
} StateResult;

typedef struct GumboInternalTagState {
  GumboStringBuffer _buffer;

  const char* _original_text;

  GumboTag _tag;

  GumboSourcePosition _start_pos;

  GumboVector   _attributes;

  bool _drop_next_attr_value;

  GumboTokenizerEnum _attr_value_state;

  GumboTag _last_start_tag;

  bool _is_start_tag;

  bool _is_self_closing;
} GumboTagState;

typedef struct GumboInternalTokenizerState {
  GumboTokenizerEnum _state;

  bool _reconsume_current_input;

  bool _is_current_node_foreign;

  bool _is_in_cdata;

  int _buffered_emit_char;

  GumboStringBuffer _temporary_buffer;

  const char* _temporary_buffer_emit;

  GumboStringBuffer _script_data_buffer;

  const char* _token_start;

  GumboSourcePosition _token_start_pos;

  GumboTagState _tag_state;

  GumboTokenDocType _doc_type_state;

  Utf8Iterator _input;
} GumboTokenizerState;

static void tokenizer_add_parse_error(
    GumboParser* parser, GumboErrorType type) {
  GumboError* error = gumbo_add_error(parser);
  if (!error) {
    return;
  }
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  utf8iterator_get_position(&tokenizer->_input, &error->position);
  error->original_text = utf8iterator_get_char_pointer(&tokenizer->_input);
  error->type = type;
  error->v.tokenizer.codepoint = utf8iterator_current(&tokenizer->_input);
  switch (tokenizer->_state) {
    case GUMBO_LEX_DATA:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_DATA;
      break;
    case GUMBO_LEX_CHAR_REF_IN_DATA:
    case GUMBO_LEX_CHAR_REF_IN_RCDATA:
    case GUMBO_LEX_CHAR_REF_IN_ATTR_VALUE:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_CHAR_REF;
      break;
    case GUMBO_LEX_RCDATA:
    case GUMBO_LEX_RCDATA_LT:
    case GUMBO_LEX_RCDATA_END_TAG_OPEN:
    case GUMBO_LEX_RCDATA_END_TAG_NAME:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_RCDATA;
      break;
    case GUMBO_LEX_RAWTEXT:
    case GUMBO_LEX_RAWTEXT_LT:
    case GUMBO_LEX_RAWTEXT_END_TAG_OPEN:
    case GUMBO_LEX_RAWTEXT_END_TAG_NAME:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_RAWTEXT;
      break;
    case GUMBO_LEX_PLAINTEXT:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_PLAINTEXT;
      break;
    case GUMBO_LEX_SCRIPT:
    case GUMBO_LEX_SCRIPT_LT:
    case GUMBO_LEX_SCRIPT_END_TAG_OPEN:
    case GUMBO_LEX_SCRIPT_END_TAG_NAME:
    case GUMBO_LEX_SCRIPT_ESCAPED_START:
    case GUMBO_LEX_SCRIPT_ESCAPED_START_DASH:
    case GUMBO_LEX_SCRIPT_ESCAPED:
    case GUMBO_LEX_SCRIPT_ESCAPED_DASH:
    case GUMBO_LEX_SCRIPT_ESCAPED_DASH_DASH:
    case GUMBO_LEX_SCRIPT_ESCAPED_LT:
    case GUMBO_LEX_SCRIPT_ESCAPED_END_TAG_OPEN:
    case GUMBO_LEX_SCRIPT_ESCAPED_END_TAG_NAME:
    case GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_START:
    case GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED:
    case GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_DASH:
    case GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_DASH_DASH:
    case GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_LT:
    case GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_END:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_SCRIPT;
      break;
    case GUMBO_LEX_TAG_OPEN:
    case GUMBO_LEX_END_TAG_OPEN:
    case GUMBO_LEX_TAG_NAME:
    case GUMBO_LEX_BEFORE_ATTR_NAME:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_TAG;
      break;
    case GUMBO_LEX_SELF_CLOSING_START_TAG:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_SELF_CLOSING_TAG;
      break;
    case GUMBO_LEX_ATTR_NAME:
    case GUMBO_LEX_AFTER_ATTR_NAME:
    case GUMBO_LEX_BEFORE_ATTR_VALUE:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_ATTR_NAME;
      break;
    case GUMBO_LEX_ATTR_VALUE_DOUBLE_QUOTED:
    case GUMBO_LEX_ATTR_VALUE_SINGLE_QUOTED:
    case GUMBO_LEX_ATTR_VALUE_UNQUOTED:
    case GUMBO_LEX_AFTER_ATTR_VALUE_QUOTED:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_ATTR_VALUE;
      break;
    case GUMBO_LEX_BOGUS_COMMENT:
    case GUMBO_LEX_COMMENT_START:
    case GUMBO_LEX_COMMENT_START_DASH:
    case GUMBO_LEX_COMMENT:
    case GUMBO_LEX_COMMENT_END_DASH:
    case GUMBO_LEX_COMMENT_END:
    case GUMBO_LEX_COMMENT_END_BANG:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_COMMENT;
      break;
    case GUMBO_LEX_MARKUP_DECLARATION:
    case GUMBO_LEX_DOCTYPE:
    case GUMBO_LEX_BEFORE_DOCTYPE_NAME:
    case GUMBO_LEX_DOCTYPE_NAME:
    case GUMBO_LEX_AFTER_DOCTYPE_NAME:
    case GUMBO_LEX_AFTER_DOCTYPE_PUBLIC_KEYWORD:
    case GUMBO_LEX_BEFORE_DOCTYPE_PUBLIC_ID:
    case GUMBO_LEX_DOCTYPE_PUBLIC_ID_DOUBLE_QUOTED:
    case GUMBO_LEX_DOCTYPE_PUBLIC_ID_SINGLE_QUOTED:
    case GUMBO_LEX_AFTER_DOCTYPE_PUBLIC_ID:
    case GUMBO_LEX_BETWEEN_DOCTYPE_PUBLIC_SYSTEM_ID:
    case GUMBO_LEX_AFTER_DOCTYPE_SYSTEM_KEYWORD:
    case GUMBO_LEX_BEFORE_DOCTYPE_SYSTEM_ID:
    case GUMBO_LEX_DOCTYPE_SYSTEM_ID_DOUBLE_QUOTED:
    case GUMBO_LEX_DOCTYPE_SYSTEM_ID_SINGLE_QUOTED:
    case GUMBO_LEX_AFTER_DOCTYPE_SYSTEM_ID:
    case GUMBO_LEX_BOGUS_DOCTYPE:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_DOCTYPE;
      break;
    case GUMBO_LEX_CDATA:
      error->v.tokenizer.state = GUMBO_ERR_TOKENIZER_CDATA;
      break;
  }
}

static bool is_alpha(int c) {
  return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

static int ensure_lowercase(int c) {
  return c >= 'A' && c <= 'Z' ? c + 0x20 : c;
}

static GumboTokenType get_char_token_type(bool is_in_cdata, int c) {
  if (is_in_cdata && c > 0) {
    return GUMBO_TOKEN_CDATA;
  }

  switch (c) {
    case '\t':
    case '\n':
    case '\r':
    case '\f':
    case ' ':
      return GUMBO_TOKEN_WHITESPACE;
    case 0:
      gumbo_debug("Emitted null byte.\n");
      return GUMBO_TOKEN_NULL;
    case -1:
      return GUMBO_TOKEN_EOF;
    default:
      return GUMBO_TOKEN_CHARACTER;
  }
}

static void clear_temporary_buffer(GumboParser* parser) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  assert(!tokenizer->_temporary_buffer_emit);
  utf8iterator_mark(&tokenizer->_input);
  gumbo_string_buffer_clear(parser, &tokenizer->_temporary_buffer);
  gumbo_string_buffer_clear(parser, &tokenizer->_script_data_buffer);
}

static void append_char_to_temporary_buffer(
    GumboParser* parser, int codepoint) {
  gumbo_string_buffer_append_codepoint(
      parser, codepoint, &parser->_tokenizer_state->_temporary_buffer);
}

#ifndef NDEBUG
static bool temporary_buffer_equals(GumboParser* parser, const char* text) {
  GumboStringBuffer* buffer = &parser->_tokenizer_state->_temporary_buffer;
  int text_len = strlen(text);
  return text_len == buffer->length &&
         memcmp(buffer->data, text, text_len) == 0;
}
#endif

static void doc_type_state_init(GumboParser* parser) {
  GumboTokenDocType* doc_type_state =
      &parser->_tokenizer_state->_doc_type_state;
  doc_type_state->name = NULL;
  doc_type_state->public_identifier = NULL;
  doc_type_state->system_identifier = NULL;
  doc_type_state->force_quirks = false;
  doc_type_state->has_public_identifier = false;
  doc_type_state->has_system_identifier = false;
}

static void reset_token_start_point(GumboTokenizerState* tokenizer) {
  tokenizer->_token_start = utf8iterator_get_char_pointer(&tokenizer->_input);
  utf8iterator_get_position(&tokenizer->_input, &tokenizer->_token_start_pos);
}

static void reset_tag_buffer_start_point(GumboParser* parser) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;

  utf8iterator_get_position(&tokenizer->_input, &tag_state->_start_pos);
  tag_state->_original_text = utf8iterator_get_char_pointer(&tokenizer->_input);
}

static void finish_temporary_buffer(GumboParser* parser, const char** output) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  *output =
      gumbo_string_buffer_to_string(parser, &tokenizer->_temporary_buffer);
  clear_temporary_buffer(parser);
}

static void finish_token(GumboParser* parser, GumboToken* token) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  if (!tokenizer->_reconsume_current_input) {
    utf8iterator_next(&tokenizer->_input);
  }

  token->position = tokenizer->_token_start_pos;
  token->original_text.data = tokenizer->_token_start;
  reset_token_start_point(tokenizer);
  token->original_text.length =
      tokenizer->_token_start - token->original_text.data;
  if (token->original_text.length > 0 &&
      token->original_text.data[token->original_text.length - 1] == '\r') {
    --token->original_text.length;
  }
}

static void finish_doctype_public_id(GumboParser* parser) {
  GumboTokenDocType* doc_type_state =
      &parser->_tokenizer_state->_doc_type_state;
  gumbo_parser_deallocate(parser, (void*) doc_type_state->public_identifier);
  finish_temporary_buffer(parser, &doc_type_state->public_identifier);
  doc_type_state->has_public_identifier = true;
}

static void finish_doctype_system_id(GumboParser* parser) {
  GumboTokenDocType* doc_type_state =
      &parser->_tokenizer_state->_doc_type_state;
  gumbo_parser_deallocate(parser, (void*) doc_type_state->system_identifier);
  finish_temporary_buffer(parser, &doc_type_state->system_identifier);
  doc_type_state->has_system_identifier = true;
}

static void emit_char(GumboParser* parser, int c, GumboToken* output) {
  output->type = get_char_token_type(parser->_tokenizer_state->_is_in_cdata, c);
  output->v.character = c;
  finish_token(parser, output);
}

static StateResult emit_replacement_char(
    GumboParser* parser, GumboToken* output) {
  tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
  emit_char(parser, kUtf8ReplacementChar, output);
  return RETURN_ERROR;
}

static StateResult emit_eof(GumboParser* parser, GumboToken* output) {
  emit_char(parser, -1, output);
  return RETURN_SUCCESS;
}

static bool emit_current_char(GumboParser* parser, GumboToken* output) {
  emit_char(
      parser, utf8iterator_current(&parser->_tokenizer_state->_input), output);
  return RETURN_SUCCESS;
}

static void emit_doctype(GumboParser* parser, GumboToken* output) {
  output->type = GUMBO_TOKEN_DOCTYPE;
  output->v.doc_type = parser->_tokenizer_state->_doc_type_state;
  finish_token(parser, output);
  doc_type_state_init(parser);
}

static void mark_tag_state_as_empty(GumboTagState* tag_state) {
#ifndef NDEBUG
  tag_state->_attributes = kGumboEmptyVector;
#endif
}

static StateResult emit_current_tag(GumboParser* parser, GumboToken* output) {
  GumboTagState* tag_state = &parser->_tokenizer_state->_tag_state;
  if (tag_state->_is_start_tag) {
    output->type = GUMBO_TOKEN_START_TAG;
    output->v.start_tag.tag = tag_state->_tag;
    output->v.start_tag.attributes = tag_state->_attributes;
    output->v.start_tag.is_self_closing = tag_state->_is_self_closing;
    tag_state->_last_start_tag = tag_state->_tag;
    mark_tag_state_as_empty(tag_state);
    gumbo_debug(
        "Emitted start tag %s.\n", gumbo_normalized_tagname(tag_state->_tag));
  } else {
    output->type = GUMBO_TOKEN_END_TAG;
    output->v.end_tag = tag_state->_tag;
    for (unsigned int i = 0; i < tag_state->_attributes.length; ++i) {
      gumbo_destroy_attribute(parser, tag_state->_attributes.data[i]);
    }
    gumbo_parser_deallocate(parser, tag_state->_attributes.data);
    mark_tag_state_as_empty(tag_state);
    gumbo_debug(
        "Emitted end tag %s.\n", gumbo_normalized_tagname(tag_state->_tag));
  }
  gumbo_string_buffer_destroy(parser, &tag_state->_buffer);
  finish_token(parser, output);
  gumbo_debug("Original text = %.*s.\n", output->original_text.length,
      output->original_text.data);
  assert(output->original_text.length >= 2);
  assert(output->original_text.data[0] == '<');
  assert(output->original_text.data[output->original_text.length - 1] == '>');
  return RETURN_SUCCESS;
}

static void abandon_current_tag(GumboParser* parser) {
  GumboTagState* tag_state = &parser->_tokenizer_state->_tag_state;
  for (unsigned int i = 0; i < tag_state->_attributes.length; ++i) {
    gumbo_destroy_attribute(parser, tag_state->_attributes.data[i]);
  }
  gumbo_parser_deallocate(parser, tag_state->_attributes.data);
  mark_tag_state_as_empty(tag_state);
  gumbo_string_buffer_destroy(parser, &tag_state->_buffer);
  gumbo_debug("Abandoning current tag.\n");
}

static StateResult emit_char_ref(GumboParser* parser,
    int additional_allowed_char, bool is_in_attribute, GumboToken* output) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  OneOrTwoCodepoints char_ref;
  bool status = consume_char_ref(
      parser, &tokenizer->_input, additional_allowed_char, false, &char_ref);
  if (char_ref.first != kGumboNoChar) {
    tokenizer->_reconsume_current_input = true;
    emit_char(parser, char_ref.first, output);
    tokenizer->_buffered_emit_char = char_ref.second;
  } else {
    emit_char(parser, '&', output);
  }
  return status ? RETURN_SUCCESS : RETURN_ERROR;
}

static StateResult emit_comment(GumboParser* parser, GumboToken* output) {
  output->type = GUMBO_TOKEN_COMMENT;
  finish_temporary_buffer(parser, &output->v.text);
  finish_token(parser, output);
  return RETURN_SUCCESS;
}

static bool maybe_emit_from_temporary_buffer(
    GumboParser* parser, GumboToken* output) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  const char* c = tokenizer->_temporary_buffer_emit;
  GumboStringBuffer* buffer = &tokenizer->_temporary_buffer;

  if (!c || c >= buffer->data + buffer->length) {
    tokenizer->_temporary_buffer_emit = NULL;
    return false;
  }

  assert(*c == utf8iterator_current(&tokenizer->_input));
  bool saved_reconsume_state = tokenizer->_reconsume_current_input;
  tokenizer->_reconsume_current_input = false;
  emit_char(parser, *c, output);
  ++tokenizer->_temporary_buffer_emit;
  tokenizer->_reconsume_current_input = saved_reconsume_state;
  return true;
}

static bool emit_temporary_buffer(GumboParser* parser, GumboToken* output) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  assert(tokenizer->_temporary_buffer.data);
  utf8iterator_reset(&tokenizer->_input);
  tokenizer->_temporary_buffer_emit = tokenizer->_temporary_buffer.data;
  return maybe_emit_from_temporary_buffer(parser, output);
}

static void append_char_to_tag_buffer(
    GumboParser* parser, int codepoint, bool reinitilize_position_on_first) {
  GumboStringBuffer* buffer = &parser->_tokenizer_state->_tag_state._buffer;
  if (buffer->length == 0 && reinitilize_position_on_first) {
    reset_tag_buffer_start_point(parser);
  }
  gumbo_string_buffer_append_codepoint(parser, codepoint, buffer);
}

static void initialize_tag_buffer(GumboParser* parser) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;

  gumbo_string_buffer_init(parser, &tag_state->_buffer);
  reset_tag_buffer_start_point(parser);
}

static void start_new_tag(GumboParser* parser, bool is_start_tag) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;
  int c = utf8iterator_current(&tokenizer->_input);
  assert(is_alpha(c));
  c = ensure_lowercase(c);
  assert(is_alpha(c));

  initialize_tag_buffer(parser);
  gumbo_string_buffer_append_codepoint(parser, c, &tag_state->_buffer);

  assert(tag_state->_attributes.data == NULL);
  gumbo_vector_init(parser, 1, &tag_state->_attributes);
  tag_state->_drop_next_attr_value = false;
  tag_state->_is_start_tag = is_start_tag;
  tag_state->_is_self_closing = false;
  gumbo_debug("Starting new tag.\n");
}

static void copy_over_tag_buffer(GumboParser* parser, const char** output) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;
  *output = gumbo_string_buffer_to_string(parser, &tag_state->_buffer);
}

static void copy_over_original_tag_text(GumboParser* parser,
    GumboStringPiece* original_text, GumboSourcePosition* start_pos,
    GumboSourcePosition* end_pos) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;

  original_text->data = tag_state->_original_text;
  original_text->length = utf8iterator_get_char_pointer(&tokenizer->_input) -
                          tag_state->_original_text;
  if (original_text->length > 0
      && original_text->data[original_text->length - 1] == '\r') {
    --original_text->length;
  }
  *start_pos = tag_state->_start_pos;
  utf8iterator_get_position(&tokenizer->_input, end_pos);
}

static void reinitialize_tag_buffer(GumboParser* parser) {
  gumbo_parser_deallocate(
      parser, parser->_tokenizer_state->_tag_state._buffer.data);
  initialize_tag_buffer(parser);
}

static void finish_tag_name(GumboParser* parser) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;

  tag_state->_tag =
      gumbo_tagn_enum(tag_state->_buffer.data, tag_state->_buffer.length);
  reinitialize_tag_buffer(parser);
}

static void add_duplicate_attr_error(GumboParser* parser, const char* attr_name,
    int original_index, int new_index) {
  GumboError* error = gumbo_add_error(parser);
  if (!error) {
    return;
  }
  GumboTagState* tag_state = &parser->_tokenizer_state->_tag_state;
  error->type = GUMBO_ERR_DUPLICATE_ATTR;
  error->position = tag_state->_start_pos;
  error->original_text = tag_state->_original_text;
  error->v.duplicate_attr.original_index = original_index;
  error->v.duplicate_attr.new_index = new_index;
  copy_over_tag_buffer(parser, &error->v.duplicate_attr.name);
  reinitialize_tag_buffer(parser);
}

static bool finish_attribute_name(GumboParser* parser) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  GumboTagState* tag_state = &tokenizer->_tag_state;
  tag_state->_drop_next_attr_value = false;
  assert(tag_state->_attributes.data);
  assert(tag_state->_attributes.capacity);

  GumboVector*   attributes = &tag_state->_attributes;
  for (unsigned int i = 0; i < attributes->length; ++i) {
    GumboAttribute* attr = attributes->data[i];
    if (strlen(attr->name) == tag_state->_buffer.length &&
        memcmp(attr->name, tag_state->_buffer.data,
            tag_state->_buffer.length) == 0) {
      add_duplicate_attr_error(parser, attr->name, i, attributes->length);
      tag_state->_drop_next_attr_value = true;
      return false;
    }
  }

  GumboAttribute* attr = gumbo_parser_allocate(parser, sizeof(GumboAttribute));
  attr->attr_namespace = GUMBO_ATTR_NAMESPACE_NONE;
  copy_over_tag_buffer(parser, &attr->name);
  copy_over_original_tag_text(
      parser, &attr->original_name, &attr->name_start, &attr->name_end);
  attr->value = gumbo_copy_stringz(parser, "");
  copy_over_original_tag_text(
      parser, &attr->original_value, &attr->name_start, &attr->name_end);
  gumbo_vector_add(parser, attr, attributes);
  reinitialize_tag_buffer(parser);
  return true;
}

static void finish_attribute_value(GumboParser* parser) {
  GumboTagState* tag_state = &parser->_tokenizer_state->_tag_state;
  if (tag_state->_drop_next_attr_value) {
    tag_state->_drop_next_attr_value = false;
    reinitialize_tag_buffer(parser);
    return;
  }

  GumboAttribute* attr =
      tag_state->_attributes.data[tag_state->_attributes.length - 1];
  gumbo_parser_deallocate(parser, (void*) attr->value);
  copy_over_tag_buffer(parser, &attr->value);
  copy_over_original_tag_text(
      parser, &attr->original_value, &attr->value_start, &attr->value_end);
  reinitialize_tag_buffer(parser);
}

static bool is_appropriate_end_tag(GumboParser* parser) {
  GumboTagState* tag_state = &parser->_tokenizer_state->_tag_state;
  assert(!tag_state->_is_start_tag);
  return tag_state->_last_start_tag != GUMBO_TAG_LAST &&
         tag_state->_last_start_tag == gumbo_tagn_enum(tag_state->_buffer.data,
                                           tag_state->_buffer.length);
}

void gumbo_tokenizer_state_init(
    GumboParser* parser, const char* text, size_t text_length) {
  GumboTokenizerState* tokenizer =
      gumbo_parser_allocate(parser, sizeof(GumboTokenizerState));
  parser->_tokenizer_state = tokenizer;
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
  tokenizer->_reconsume_current_input = false;
  tokenizer->_is_current_node_foreign = false;
  tokenizer->_is_in_cdata = false;
  tokenizer->_tag_state._last_start_tag = GUMBO_TAG_LAST;

  tokenizer->_buffered_emit_char = kGumboNoChar;
  gumbo_string_buffer_init(parser, &tokenizer->_temporary_buffer);
  tokenizer->_temporary_buffer_emit = NULL;

  mark_tag_state_as_empty(&tokenizer->_tag_state);

  gumbo_string_buffer_init(parser, &tokenizer->_script_data_buffer);
  tokenizer->_token_start = text;
  utf8iterator_init(parser, text, text_length, &tokenizer->_input);
  utf8iterator_get_position(&tokenizer->_input, &tokenizer->_token_start_pos);
  doc_type_state_init(parser);
}

void gumbo_tokenizer_state_destroy(GumboParser* parser) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;
  assert(tokenizer->_doc_type_state.name == NULL);
  assert(tokenizer->_doc_type_state.public_identifier == NULL);
  assert(tokenizer->_doc_type_state.system_identifier == NULL);
  gumbo_string_buffer_destroy(parser, &tokenizer->_temporary_buffer);
  gumbo_string_buffer_destroy(parser, &tokenizer->_script_data_buffer);
  gumbo_parser_deallocate(parser, tokenizer);
}

void gumbo_tokenizer_set_state(GumboParser* parser, GumboTokenizerEnum state) {
  parser->_tokenizer_state->_state = state;
}

void gumbo_tokenizer_set_is_current_node_foreign(
    GumboParser* parser, bool is_foreign) {
  if (is_foreign != parser->_tokenizer_state->_is_current_node_foreign) {
    gumbo_debug("Toggling is_current_node_foreign to %s.\n",
        is_foreign ? "true" : "false");
  }
  parser->_tokenizer_state->_is_current_node_foreign = is_foreign;
}

static StateResult handle_data_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '&':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_CHAR_REF_IN_DATA);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_TAG_OPEN);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, '<');
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      emit_char(parser, c, output);
      return RETURN_ERROR;
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_char_ref_in_data_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
  return emit_char_ref(parser, ' ', false, output);
}

static StateResult handle_rcdata_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '&':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_CHAR_REF_IN_RCDATA);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA_LT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, '<');
      return NEXT_CHAR;
    case '\0':
      return emit_replacement_char(parser, output);
    case -1:
      return emit_eof(parser, output);
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_char_ref_in_rcdata_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA);
  return emit_char_ref(parser, ' ', false, output);
}

static StateResult handle_rawtext_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT_LT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, '<');
      return NEXT_CHAR;
    case '\0':
      return emit_replacement_char(parser, output);
    case -1:
      return emit_eof(parser, output);
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_LT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, '<');
      return NEXT_CHAR;
    case '\0':
      return emit_replacement_char(parser, output);
    case -1:
      return emit_eof(parser, output);
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_plaintext_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\0':
      return emit_replacement_char(parser, output);
    case -1:
      return emit_eof(parser, output);
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_tag_open_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "<"));
  switch (c) {
    case '!':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_MARKUP_DECLARATION);
      clear_temporary_buffer(parser);
      return NEXT_CHAR;
    case '/':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_END_TAG_OPEN);
      append_char_to_temporary_buffer(parser, '/');
      return NEXT_CHAR;
    case '?':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_COMMENT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, '?');
      tokenizer_add_parse_error(parser, GUMBO_ERR_TAG_STARTS_WITH_QUESTION);
      return NEXT_CHAR;
    default:
      if (is_alpha(c)) {
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_TAG_NAME);
        start_new_tag(parser, true);
        return NEXT_CHAR;
      } else {
        tokenizer_add_parse_error(parser, GUMBO_ERR_TAG_INVALID);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
        emit_temporary_buffer(parser, output);
        return RETURN_ERROR;
      }
  }
}

static StateResult handle_end_tag_open_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "</"));
  switch (c) {
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_CLOSE_TAG_EMPTY);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_CLOSE_TAG_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_temporary_buffer(parser, output);
    default:
      if (is_alpha(c)) {
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_TAG_NAME);
        start_new_tag(parser, false);
      } else {
        tokenizer_add_parse_error(parser, GUMBO_ERR_CLOSE_TAG_INVALID);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_COMMENT);
        clear_temporary_buffer(parser);
        append_char_to_temporary_buffer(parser, c);
      }
      return NEXT_CHAR;
  }
}

static StateResult handle_tag_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      finish_tag_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
      return NEXT_CHAR;
    case '/':
      finish_tag_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
      return NEXT_CHAR;
    case '>':
      finish_tag_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_current_tag(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_tag_buffer(parser, kUtf8ReplacementChar, true);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_TAG_EOF);
      abandon_current_tag(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    default:
      append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
      return NEXT_CHAR;
  }
}

static StateResult handle_rcdata_lt_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "<"));
  if (c == '/') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA_END_TAG_OPEN);
    append_char_to_temporary_buffer(parser, '/');
    return NEXT_CHAR;
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA);
    tokenizer->_reconsume_current_input = true;
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_rcdata_end_tag_open_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "</"));
  if (is_alpha(c)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA_END_TAG_NAME);
    start_new_tag(parser, false);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA);
    return emit_temporary_buffer(parser, output);
  }
  return true;
}

static StateResult handle_rcdata_end_tag_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(tokenizer->_temporary_buffer.length >= 2);
  if (is_alpha(c)) {
    append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else if (is_appropriate_end_tag(parser)) {
    switch (c) {
      case '\t':
      case '\n':
      case '\f':
      case ' ':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
        return NEXT_CHAR;
      case '/':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
        return NEXT_CHAR;
      case '>':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
        return emit_current_tag(parser, output);
    }
  }
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA);
  abandon_current_tag(parser);
  return emit_temporary_buffer(parser, output);
}

static StateResult handle_rawtext_lt_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "<"));
  if (c == '/') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT_END_TAG_OPEN);
    append_char_to_temporary_buffer(parser, '/');
    return NEXT_CHAR;
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT);
    tokenizer->_reconsume_current_input = true;
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_rawtext_end_tag_open_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "</"));
  if (is_alpha(c)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT_END_TAG_NAME);
    start_new_tag(parser, false);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT);
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_rawtext_end_tag_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(tokenizer->_temporary_buffer.length >= 2);
  gumbo_debug("Last end tag: %*s\n", (int) tokenizer->_tag_state._buffer.length,
      tokenizer->_tag_state._buffer.data);
  if (is_alpha(c)) {
    append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else if (is_appropriate_end_tag(parser)) {
    gumbo_debug("Is an appropriate end tag.\n");
    switch (c) {
      case '\t':
      case '\n':
      case '\f':
      case ' ':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
        return NEXT_CHAR;
      case '/':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
        return NEXT_CHAR;
      case '>':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
        return emit_current_tag(parser, output);
    }
  }
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT);
  abandon_current_tag(parser);
  return emit_temporary_buffer(parser, output);
}

static StateResult handle_script_lt_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "<"));
  if (c == '/') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_END_TAG_OPEN);
    append_char_to_temporary_buffer(parser, '/');
    return NEXT_CHAR;
  } else if (c == '!') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_START);
    append_char_to_temporary_buffer(parser, '!');
    return emit_temporary_buffer(parser, output);
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
    tokenizer->_reconsume_current_input = true;
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_script_end_tag_open_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "</"));
  if (is_alpha(c)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_END_TAG_NAME);
    start_new_tag(parser, false);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_script_end_tag_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(tokenizer->_temporary_buffer.length >= 2);
  if (is_alpha(c)) {
    append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else if (is_appropriate_end_tag(parser)) {
    switch (c) {
      case '\t':
      case '\n':
      case '\f':
      case ' ':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
        return NEXT_CHAR;
      case '/':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
        return NEXT_CHAR;
      case '>':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
        return emit_current_tag(parser, output);
    }
  }
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
  abandon_current_tag(parser);
  return emit_temporary_buffer(parser, output);
}

static StateResult handle_script_escaped_start_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  if (c == '-') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_START_DASH);
    return emit_current_char(parser, output);
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
    tokenizer->_reconsume_current_input = true;
    return NEXT_CHAR;
  }
}

static StateResult handle_script_escaped_start_dash_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  if (c == '-') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_DASH_DASH);
    return emit_current_char(parser, output);
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
    tokenizer->_reconsume_current_input = true;
    return NEXT_CHAR;
  }
}

static StateResult handle_script_escaped_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_DASH);
      return emit_current_char(parser, output);
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_LT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
    case '\0':
      return emit_replacement_char(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SCRIPT_EOF);
      return emit_eof(parser, output);
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_escaped_dash_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_DASH_DASH);
      return emit_current_char(parser, output);
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_LT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
    case '\0':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
      return emit_replacement_char(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SCRIPT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_escaped_dash_dash_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      return emit_current_char(parser, output);
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_LT);
      clear_temporary_buffer(parser);
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
      return emit_current_char(parser, output);
    case '\0':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
      return emit_replacement_char(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SCRIPT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_escaped_lt_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "<"));
  assert(!tokenizer->_script_data_buffer.length);
  if (c == '/') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_END_TAG_OPEN);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else if (is_alpha(c)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_START);
    append_char_to_temporary_buffer(parser, c);
    gumbo_string_buffer_append_codepoint(
        parser, ensure_lowercase(c), &tokenizer->_script_data_buffer);
    return emit_temporary_buffer(parser, output);
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_script_escaped_end_tag_open_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(temporary_buffer_equals(parser, "</"));
  if (is_alpha(c)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED_END_TAG_NAME);
    start_new_tag(parser, false);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
    return emit_temporary_buffer(parser, output);
  }
}

static StateResult handle_script_escaped_end_tag_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(tokenizer->_temporary_buffer.length >= 2);
  if (is_alpha(c)) {
    append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
    append_char_to_temporary_buffer(parser, c);
    return NEXT_CHAR;
  } else if (is_appropriate_end_tag(parser)) {
    switch (c) {
      case '\t':
      case '\n':
      case '\f':
      case ' ':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
        return NEXT_CHAR;
      case '/':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
        return NEXT_CHAR;
      case '>':
        finish_tag_name(parser);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
        return emit_current_tag(parser, output);
    }
  }
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
  abandon_current_tag(parser);
  return emit_temporary_buffer(parser, output);
}

static StateResult handle_script_double_escaped_start_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
    case '/':
    case '>':
      gumbo_tokenizer_set_state(
          parser, gumbo_string_equals(&kScriptTag,
                      (GumboStringPiece*) &tokenizer->_script_data_buffer)
                      ? GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED
                      : GUMBO_LEX_SCRIPT_ESCAPED);
      return emit_current_char(parser, output);
    default:
      if (is_alpha(c)) {
        gumbo_string_buffer_append_codepoint(
            parser, ensure_lowercase(c), &tokenizer->_script_data_buffer);
        return emit_current_char(parser, output);
      } else {
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_ESCAPED);
        tokenizer->_reconsume_current_input = true;
        return NEXT_CHAR;
      }
  }
}

static StateResult handle_script_double_escaped_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_DASH);
      return emit_current_char(parser, output);
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_LT);
      return emit_current_char(parser, output);
    case '\0':
      return emit_replacement_char(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SCRIPT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    default:
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_double_escaped_dash_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_DASH_DASH);
      return emit_current_char(parser, output);
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_LT);
      return emit_current_char(parser, output);
    case '\0':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
      return emit_replacement_char(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SCRIPT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_double_escaped_dash_dash_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '-':
      return emit_current_char(parser, output);
    case '<':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_LT);
      return emit_current_char(parser, output);
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
      return emit_current_char(parser, output);
    case '\0':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
      return emit_replacement_char(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SCRIPT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return NEXT_CHAR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
      return emit_current_char(parser, output);
  }
}

static StateResult handle_script_double_escaped_lt_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  if (c == '/') {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED_END);
    gumbo_string_buffer_clear(parser, &tokenizer->_script_data_buffer);
    return emit_current_char(parser, output);
  } else {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
    tokenizer->_reconsume_current_input = true;
    return NEXT_CHAR;
  }
}

static StateResult handle_script_double_escaped_end_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
    case '/':
    case '>':
      gumbo_tokenizer_set_state(
          parser, gumbo_string_equals(&kScriptTag,
                      (GumboStringPiece*) &tokenizer->_script_data_buffer)
                      ? GUMBO_LEX_SCRIPT_ESCAPED
                      : GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
      return emit_current_char(parser, output);
    default:
      if (is_alpha(c)) {
        gumbo_string_buffer_append_codepoint(
            parser, ensure_lowercase(c), &tokenizer->_script_data_buffer);
        return emit_current_char(parser, output);
      } else {
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT_DOUBLE_ESCAPED);
        tokenizer->_reconsume_current_input = true;
        return NEXT_CHAR;
      }
  }
}

static StateResult handle_before_attr_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '/':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_current_tag(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_NAME);
      append_char_to_temporary_buffer(parser, 0xfffd);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_NAME_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      return NEXT_CHAR;
    case '"':
    case '\'':
    case '<':
    case '=':
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_NAME_INVALID);
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_NAME);
      append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
      return NEXT_CHAR;
  }
}

static StateResult handle_attr_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      finish_attribute_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_ATTR_NAME);
      return NEXT_CHAR;
    case '/':
      finish_attribute_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
      return NEXT_CHAR;
    case '=':
      finish_attribute_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_VALUE);
      return NEXT_CHAR;
    case '>':
      finish_attribute_name(parser);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_current_tag(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_tag_buffer(parser, kUtf8ReplacementChar, true);
      return NEXT_CHAR;
    case -1:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_NAME_EOF);
      return NEXT_CHAR;
    case '"':
    case '\'':
    case '<':
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_NAME_INVALID);
    default:
      append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
      return NEXT_CHAR;
  }
}

static StateResult handle_after_attr_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '/':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
      return NEXT_CHAR;
    case '=':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_VALUE);
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_current_tag(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_NAME);
      append_char_to_temporary_buffer(parser, 0xfffd);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_NAME_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      return NEXT_CHAR;
    case '"':
    case '\'':
    case '<':
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_NAME_INVALID);
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_NAME);
      append_char_to_tag_buffer(parser, ensure_lowercase(c), true);
      return NEXT_CHAR;
  }
}

static StateResult handle_before_attr_value_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '"':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_VALUE_DOUBLE_QUOTED);
      reset_tag_buffer_start_point(parser);
      return NEXT_CHAR;
    case '&':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_VALUE_UNQUOTED);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '\'':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_VALUE_SINGLE_QUOTED);
      reset_tag_buffer_start_point(parser);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_VALUE_UNQUOTED);
      append_char_to_tag_buffer(parser, kUtf8ReplacementChar, true);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_UNQUOTED_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_UNQUOTED_RIGHT_BRACKET);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_current_tag(parser, output);
      return RETURN_ERROR;
    case '<':
    case '=':
    case '`':
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_UNQUOTED_EQUALS);
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_ATTR_VALUE_UNQUOTED);
      append_char_to_tag_buffer(parser, c, true);
      return NEXT_CHAR;
  }
}

static StateResult handle_attr_value_double_quoted_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '"':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_ATTR_VALUE_QUOTED);
      return NEXT_CHAR;
    case '&':
      tokenizer->_tag_state._attr_value_state = tokenizer->_state;
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_CHAR_REF_IN_ATTR_VALUE);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_tag_buffer(parser, kUtf8ReplacementChar, false);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_DOUBLE_QUOTE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    default:
      append_char_to_tag_buffer(parser, c, false);
      return NEXT_CHAR;
  }
}

static StateResult handle_attr_value_single_quoted_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\'':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_ATTR_VALUE_QUOTED);
      return NEXT_CHAR;
    case '&':
      tokenizer->_tag_state._attr_value_state = tokenizer->_state;
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_CHAR_REF_IN_ATTR_VALUE);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_tag_buffer(parser, kUtf8ReplacementChar, false);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_SINGLE_QUOTE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    default:
      append_char_to_tag_buffer(parser, c, false);
      return NEXT_CHAR;
  }
}

static StateResult handle_attr_value_unquoted_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
      finish_attribute_value(parser);
      return NEXT_CHAR;
    case '&':
      tokenizer->_tag_state._attr_value_state = tokenizer->_state;
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_CHAR_REF_IN_ATTR_VALUE);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      finish_attribute_value(parser);
      return emit_current_tag(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_tag_buffer(parser, kUtf8ReplacementChar, true);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_UNQUOTED_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_reconsume_current_input = true;
      abandon_current_tag(parser);
      return NEXT_CHAR;
    case '<':
    case '=':
    case '"':
    case '\'':
    case '`':
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_UNQUOTED_EQUALS);
    default:
      append_char_to_tag_buffer(parser, c, true);
      return NEXT_CHAR;
  }
}

static StateResult handle_char_ref_in_attr_value_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  OneOrTwoCodepoints char_ref;
  int allowed_char;
  bool is_unquoted = false;
  switch (tokenizer->_tag_state._attr_value_state) {
    case GUMBO_LEX_ATTR_VALUE_DOUBLE_QUOTED:
      allowed_char = '"';
      break;
    case GUMBO_LEX_ATTR_VALUE_SINGLE_QUOTED:
      allowed_char = '\'';
      break;
    case GUMBO_LEX_ATTR_VALUE_UNQUOTED:
      allowed_char = '>';
      is_unquoted = true;
      break;
    default:
      allowed_char = ' ';
      assert(0);
  }

  consume_char_ref(parser, &tokenizer->_input, allowed_char, true, &char_ref);
  if (char_ref.first != kGumboNoChar) {
    tokenizer->_reconsume_current_input = true;
    append_char_to_tag_buffer(parser, char_ref.first, is_unquoted);
    if (char_ref.second != kGumboNoChar) {
      append_char_to_tag_buffer(parser, char_ref.second, is_unquoted);
    }
  } else {
    append_char_to_tag_buffer(parser, '&', is_unquoted);
  }
  gumbo_tokenizer_set_state(parser, tokenizer->_tag_state._attr_value_state);
  return NEXT_CHAR;
}

static StateResult handle_after_attr_value_quoted_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  finish_attribute_value(parser);
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
      return NEXT_CHAR;
    case '/':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_SELF_CLOSING_START_TAG);
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_current_tag(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_AFTER_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_ATTR_AFTER_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
  }
}

static StateResult handle_self_closing_start_tag_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_tag_state._is_self_closing = true;
      return emit_current_tag(parser, output);
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SOLIDUS_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      abandon_current_tag(parser);
      return NEXT_CHAR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_SOLIDUS_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_ATTR_NAME);
      tokenizer->_reconsume_current_input = true;
      return NEXT_CHAR;
  }
}

static StateResult handle_bogus_comment_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  while (c != '>' && c != -1) {
    if (c == '\0') {
      c = 0xFFFD;
    }
    append_char_to_temporary_buffer(parser, c);
    utf8iterator_next(&tokenizer->_input);
    c = utf8iterator_current(&tokenizer->_input);
  }
  gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
  return emit_comment(parser, output);
}

static StateResult handle_markup_declaration_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  if (utf8iterator_maybe_consume_match(
          &tokenizer->_input, "--", sizeof("--") - 1, true)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_START);
    tokenizer->_reconsume_current_input = true;
  } else if (utf8iterator_maybe_consume_match(
                 &tokenizer->_input, "DOCTYPE", sizeof("DOCTYPE") - 1, false)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_DOCTYPE);
    tokenizer->_reconsume_current_input = true;
    tokenizer->_doc_type_state.name = gumbo_copy_stringz(parser, "");
    tokenizer->_doc_type_state.public_identifier =
        gumbo_copy_stringz(parser, "");
    tokenizer->_doc_type_state.system_identifier =
        gumbo_copy_stringz(parser, "");
  } else if (tokenizer->_is_current_node_foreign &&
             utf8iterator_maybe_consume_match(
                 &tokenizer->_input, "[CDATA[", sizeof("[CDATA[") - 1, true)) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_CDATA);
    tokenizer->_is_in_cdata = true;
    tokenizer->_reconsume_current_input = true;
  } else {
    tokenizer_add_parse_error(parser, GUMBO_ERR_DASHES_OR_DOCTYPE);
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_COMMENT);
    tokenizer->_reconsume_current_input = true;
    clear_temporary_buffer(parser);
  }
  return NEXT_CHAR;
}

static StateResult handle_comment_start_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_START_DASH);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_comment_start_dash_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_END);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_comment_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_END_DASH);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    default:
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_comment_end_dash_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_END);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_comment_end_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_comment(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '!':
      tokenizer_add_parse_error(
          parser, GUMBO_ERR_COMMENT_BANG_AFTER_DOUBLE_DASH);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_END_BANG);
      return NEXT_CHAR;
    case '-':
      tokenizer_add_parse_error(
          parser, GUMBO_ERR_COMMENT_DASH_AFTER_DOUBLE_DASH);
      append_char_to_temporary_buffer(parser, '-');
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_comment_end_bang_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '-':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT_END_DASH);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '!');
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      return emit_comment(parser, output);
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '!');
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_COMMENT_END_BANG_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_comment(parser, output);
      return RETURN_ERROR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_COMMENT);
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '-');
      append_char_to_temporary_buffer(parser, '!');
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_doctype_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  assert(!tokenizer->_temporary_buffer.length);
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_DOCTYPE_NAME);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_SPACE);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_DOCTYPE_NAME);
      tokenizer->_reconsume_current_input = true;
      tokenizer->_doc_type_state.force_quirks = true;
      return NEXT_CHAR;
  }
}

static StateResult handle_before_doctype_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DOCTYPE_NAME);
      tokenizer->_doc_type_state.force_quirks = true;
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_RIGHT_BRACKET);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DOCTYPE_NAME);
      tokenizer->_doc_type_state.force_quirks = false;
      append_char_to_temporary_buffer(parser, ensure_lowercase(c));
      return NEXT_CHAR;
  }
}

static StateResult handle_doctype_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_DOCTYPE_NAME);
      gumbo_parser_deallocate(parser, (void*) tokenizer->_doc_type_state.name);
      finish_temporary_buffer(parser, &tokenizer->_doc_type_state.name);
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      gumbo_parser_deallocate(parser, (void*) tokenizer->_doc_type_state.name);
      finish_temporary_buffer(parser, &tokenizer->_doc_type_state.name);
      emit_doctype(parser, output);
      return RETURN_SUCCESS;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      gumbo_parser_deallocate(parser, (void*) tokenizer->_doc_type_state.name);
      finish_temporary_buffer(parser, &tokenizer->_doc_type_state.name);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DOCTYPE_NAME);
      tokenizer->_doc_type_state.force_quirks = false;
      append_char_to_temporary_buffer(parser, ensure_lowercase(c));
      return NEXT_CHAR;
  }
}

static StateResult handle_after_doctype_name_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_doctype(parser, output);
      return RETURN_SUCCESS;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      if (utf8iterator_maybe_consume_match(
              &tokenizer->_input, "PUBLIC", sizeof("PUBLIC") - 1, false)) {
        gumbo_tokenizer_set_state(
            parser, GUMBO_LEX_AFTER_DOCTYPE_PUBLIC_KEYWORD);
        tokenizer->_reconsume_current_input = true;
      } else if (utf8iterator_maybe_consume_match(&tokenizer->_input, "SYSTEM",
                     sizeof("SYSTEM") - 1, false)) {
        gumbo_tokenizer_set_state(
            parser, GUMBO_LEX_AFTER_DOCTYPE_SYSTEM_KEYWORD);
        tokenizer->_reconsume_current_input = true;
      } else {
        tokenizer_add_parse_error(
            parser, GUMBO_ERR_DOCTYPE_SPACE_OR_RIGHT_BRACKET);
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
        tokenizer->_doc_type_state.force_quirks = true;
      }
      return NEXT_CHAR;
  }
}

static StateResult handle_after_doctype_public_keyword_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_DOCTYPE_PUBLIC_ID);
      return NEXT_CHAR;
    case '"':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_PUBLIC_ID_DOUBLE_QUOTED);
      return NEXT_CHAR;
    case '\'':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_PUBLIC_ID_SINGLE_QUOTED);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_RIGHT_BRACKET);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
  }
}

static StateResult handle_before_doctype_public_id_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '"':
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_PUBLIC_ID_DOUBLE_QUOTED);
      return NEXT_CHAR;
    case '\'':
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_PUBLIC_ID_SINGLE_QUOTED);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
  }
}

static StateResult handle_doctype_public_id_double_quoted_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '"':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_DOCTYPE_PUBLIC_ID);
      finish_doctype_public_id(parser);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_public_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_public_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_doctype_public_id_single_quoted_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '\'':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_DOCTYPE_PUBLIC_ID);
      finish_doctype_public_id(parser);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_public_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_public_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_after_doctype_public_id_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_BETWEEN_DOCTYPE_PUBLIC_SYSTEM_ID);
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_doctype(parser, output);
      return RETURN_SUCCESS;
    case '"':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_DOUBLE_QUOTED);
      return NEXT_CHAR;
    case '\'':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_SINGLE_QUOTED);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_reconsume_current_input = true;
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      tokenizer->_doc_type_state.force_quirks = true;
      return NEXT_CHAR;
  }
}

static StateResult handle_between_doctype_public_system_id_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_doctype(parser, output);
      return RETURN_SUCCESS;
    case '"':
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_DOUBLE_QUOTED);
      return NEXT_CHAR;
    case '\'':
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_SINGLE_QUOTED);
      return NEXT_CHAR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
  }
}

static StateResult handle_after_doctype_system_keyword_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BEFORE_DOCTYPE_SYSTEM_ID);
      return NEXT_CHAR;
    case '"':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_DOUBLE_QUOTED);
      return NEXT_CHAR;
    case '\'':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_SINGLE_QUOTED);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      tokenizer->_doc_type_state.force_quirks = true;
      return NEXT_CHAR;
  }
}

static StateResult handle_before_doctype_system_id_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '"':
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_DOUBLE_QUOTED);
      return NEXT_CHAR;
    case '\'':
      assert(temporary_buffer_equals(parser, ""));
      gumbo_tokenizer_set_state(
          parser, GUMBO_LEX_DOCTYPE_SYSTEM_ID_SINGLE_QUOTED);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      tokenizer->_doc_type_state.force_quirks = true;
      return NEXT_CHAR;
  }
}

static StateResult handle_doctype_system_id_double_quoted_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '"':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_DOCTYPE_SYSTEM_ID);
      finish_doctype_system_id(parser);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_system_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_system_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_doctype_system_id_single_quoted_state(
    GumboParser* parser, GumboTokenizerState* tokenizer, int c,
    GumboToken* output) {
  switch (c) {
    case '\'':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_AFTER_DOCTYPE_SYSTEM_ID);
      finish_doctype_system_id(parser);
      return NEXT_CHAR;
    case '\0':
      tokenizer_add_parse_error(parser, GUMBO_ERR_UTF8_NULL);
      append_char_to_temporary_buffer(parser, kUtf8ReplacementChar);
      return NEXT_CHAR;
    case '>':
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_END);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_system_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      finish_doctype_system_id(parser);
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      append_char_to_temporary_buffer(parser, c);
      return NEXT_CHAR;
  }
}

static StateResult handle_after_doctype_system_id_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  switch (c) {
    case '\t':
    case '\n':
    case '\f':
    case ' ':
      return NEXT_CHAR;
    case '>':
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      emit_doctype(parser, output);
      return RETURN_SUCCESS;
    case -1:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_EOF);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
      tokenizer->_doc_type_state.force_quirks = true;
      emit_doctype(parser, output);
      return RETURN_ERROR;
    default:
      tokenizer_add_parse_error(parser, GUMBO_ERR_DOCTYPE_INVALID);
      gumbo_tokenizer_set_state(parser, GUMBO_LEX_BOGUS_DOCTYPE);
      return NEXT_CHAR;
  }
}

static StateResult handle_bogus_doctype_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  if (c == '>' || c == -1) {
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
    emit_doctype(parser, output);
    return RETURN_ERROR;
  }
  return NEXT_CHAR;
}

static StateResult handle_cdata_state(GumboParser* parser,
    GumboTokenizerState* tokenizer, int c, GumboToken* output) {
  if (c == -1 || utf8iterator_maybe_consume_match(
                     &tokenizer->_input, "]]>", sizeof("]]>") - 1, true)) {
    tokenizer->_reconsume_current_input = true;
    reset_token_start_point(tokenizer);
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_DATA);
    tokenizer->_is_in_cdata = false;
    return NEXT_CHAR;
  } else {
    return emit_current_char(parser, output);
  }
}

typedef StateResult (*GumboLexerStateFunction)(
    GumboParser*, GumboTokenizerState*, int, GumboToken*);

static GumboLexerStateFunction dispatch_table[] = {handle_data_state,
    handle_char_ref_in_data_state, handle_rcdata_state,
    handle_char_ref_in_rcdata_state, handle_rawtext_state, handle_script_state,
    handle_plaintext_state, handle_tag_open_state, handle_end_tag_open_state,
    handle_tag_name_state, handle_rcdata_lt_state,
    handle_rcdata_end_tag_open_state, handle_rcdata_end_tag_name_state,
    handle_rawtext_lt_state, handle_rawtext_end_tag_open_state,
    handle_rawtext_end_tag_name_state, handle_script_lt_state,
    handle_script_end_tag_open_state, handle_script_end_tag_name_state,
    handle_script_escaped_start_state, handle_script_escaped_start_dash_state,
    handle_script_escaped_state, handle_script_escaped_dash_state,
    handle_script_escaped_dash_dash_state, handle_script_escaped_lt_state,
    handle_script_escaped_end_tag_open_state,
    handle_script_escaped_end_tag_name_state,
    handle_script_double_escaped_start_state,
    handle_script_double_escaped_state, handle_script_double_escaped_dash_state,
    handle_script_double_escaped_dash_dash_state,
    handle_script_double_escaped_lt_state,
    handle_script_double_escaped_end_state, handle_before_attr_name_state,
    handle_attr_name_state, handle_after_attr_name_state,
    handle_before_attr_value_state, handle_attr_value_double_quoted_state,
    handle_attr_value_single_quoted_state, handle_attr_value_unquoted_state,
    handle_char_ref_in_attr_value_state, handle_after_attr_value_quoted_state,
    handle_self_closing_start_tag_state, handle_bogus_comment_state,
    handle_markup_declaration_state, handle_comment_start_state,
    handle_comment_start_dash_state, handle_comment_state,
    handle_comment_end_dash_state, handle_comment_end_state,
    handle_comment_end_bang_state, handle_doctype_state,
    handle_before_doctype_name_state, handle_doctype_name_state,
    handle_after_doctype_name_state, handle_after_doctype_public_keyword_state,
    handle_before_doctype_public_id_state,
    handle_doctype_public_id_double_quoted_state,
    handle_doctype_public_id_single_quoted_state,
    handle_after_doctype_public_id_state,
    handle_between_doctype_public_system_id_state,
    handle_after_doctype_system_keyword_state,
    handle_before_doctype_system_id_state,
    handle_doctype_system_id_double_quoted_state,
    handle_doctype_system_id_single_quoted_state,
    handle_after_doctype_system_id_state, handle_bogus_doctype_state,
    handle_cdata_state};

bool gumbo_lex(GumboParser* parser, GumboToken* output) {
  GumboTokenizerState* tokenizer = parser->_tokenizer_state;

  if (tokenizer->_buffered_emit_char != kGumboNoChar) {
    tokenizer->_reconsume_current_input = true;
    emit_char(parser, tokenizer->_buffered_emit_char, output);
    tokenizer->_reconsume_current_input = false;
    tokenizer->_buffered_emit_char = kGumboNoChar;
    return true;
  }

  if (maybe_emit_from_temporary_buffer(parser, output)) {
    return true;
  }

  while (1) {
    assert(!tokenizer->_temporary_buffer_emit);
    assert(tokenizer->_buffered_emit_char == kGumboNoChar);
    int c = utf8iterator_current(&tokenizer->_input);
    gumbo_debug(
        "Lexing character '%c' (%d) in state %d.\n", c, c, tokenizer->_state);
    StateResult result =
        dispatch_table[tokenizer->_state](parser, tokenizer, c, output);
    bool should_advance = !tokenizer->_reconsume_current_input;
    tokenizer->_reconsume_current_input = false;

    if (result == RETURN_SUCCESS) {
      return true;
    } else if (result == RETURN_ERROR) {
      return false;
    }

    if (should_advance) {
      utf8iterator_next(&tokenizer->_input);
    }
  }
}

void gumbo_token_destroy(GumboParser* parser, GumboToken* token) {
  if (!token) return;

  switch (token->type) {
    case GUMBO_TOKEN_DOCTYPE:
      gumbo_parser_deallocate(parser, (void*) token->v.doc_type.name);
      gumbo_parser_deallocate(
          parser, (void*) token->v.doc_type.public_identifier);
      gumbo_parser_deallocate(
          parser, (void*) token->v.doc_type.system_identifier);
      return;
    case GUMBO_TOKEN_START_TAG:
      for (unsigned int i = 0; i < token->v.start_tag.attributes.length; ++i) {
        GumboAttribute* attr = token->v.start_tag.attributes.data[i];
        if (attr) {
          gumbo_destroy_attribute(parser, attr);
        }
      }
      gumbo_parser_deallocate(
          parser, (void*) token->v.start_tag.attributes.data);
      return;
    case GUMBO_TOKEN_COMMENT:
      gumbo_parser_deallocate(parser, (void*) token->v.text);
      return;
    default:
      return;
  }
}

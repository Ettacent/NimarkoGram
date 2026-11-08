/* Modifications Copyright (C) 2026 Ettacent */

// Copyright 2010 Google Inc. All Rights Reserved.
// Licensed under the Apache License, Version 2.0 (the "License");

#include <assert.h>
#include <ctype.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "attribute.h"
#include "error.h"
#include "gumbo.h"
#include "insertion_mode.h"
#include "parser.h"
#include "tokenizer.h"
#include "tokenizer_states.h"
#include "utf8.h"
#include "util.h"
#include "vector.h"

#define AVOID_UNUSED_VARIABLE_WARNING(i) (void)(i)

#define GUMBO_STRING(literal) \
  { literal, sizeof(literal) - 1 }
#define TERMINATOR \
  { "", 0 }

typedef char gumbo_tagset[GUMBO_TAG_LAST];
#define TAG(tag) [GUMBO_TAG_##tag] = (1 << GUMBO_NAMESPACE_HTML)
#define TAG_SVG(tag) [GUMBO_TAG_##tag] = (1 << GUMBO_NAMESPACE_SVG)
#define TAG_MATHML(tag) [GUMBO_TAG_##tag] = (1 << GUMBO_NAMESPACE_MATHML)

#define TAGSET_INCLUDES(tagset, namespace, tag) \
  (tag < GUMBO_TAG_LAST && tagset[(int) tag] & (1 << (int) namespace))

static bool node_html_tag_is(const GumboNode*, GumboTag);
static GumboInsertionMode get_current_template_insertion_mode(
    const GumboParser*);
static bool handle_in_template(GumboParser*, GumboToken*);
static void destroy_node(GumboParser*, GumboNode*);

static void* malloc_wrapper(void* unused, size_t size) { return malloc(size); }

static void free_wrapper(void* unused, void* ptr) { free(ptr); }

const GumboOptions kGumboDefaultOptions = {&malloc_wrapper, &free_wrapper, NULL,
    8, false, -1, GUMBO_TAG_LAST, GUMBO_NAMESPACE_HTML};

static const GumboStringPiece kDoctypeHtml = GUMBO_STRING("html");
static const GumboStringPiece kPublicIdHtml4_0 =
    GUMBO_STRING("-//W3C//DTD HTML 4.0//EN");
static const GumboStringPiece kPublicIdHtml4_01 =
    GUMBO_STRING("-//W3C//DTD HTML 4.01//EN");
static const GumboStringPiece kPublicIdXhtml1_0 =
    GUMBO_STRING("-//W3C//DTD XHTML 1.0 Strict//EN");
static const GumboStringPiece kPublicIdXhtml1_1 =
    GUMBO_STRING("-//W3C//DTD XHTML 1.1//EN");
static const GumboStringPiece kSystemIdRecHtml4_0 =
    GUMBO_STRING("http://www.w3.org/TR/REC-html40/strict.dtd");
static const GumboStringPiece kSystemIdHtml4 =
    GUMBO_STRING("http://www.w3.org/TR/html4/strict.dtd");
static const GumboStringPiece kSystemIdXhtmlStrict1_1 =
    GUMBO_STRING("http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd");
static const GumboStringPiece kSystemIdXhtml1_1 =
    GUMBO_STRING("http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd");
static const GumboStringPiece kSystemIdLegacyCompat =
    GUMBO_STRING("about:legacy-compat");


static const GumboStringPiece kQuirksModePublicIdPrefixes[] = {
    GUMBO_STRING("+//Silmaril//dtd html Pro v0r11 19970101//"),
    GUMBO_STRING("-//AdvaSoft Ltd//DTD HTML 3.0 asWedit + extensions//"),
    GUMBO_STRING("-//AS//DTD HTML 3.0 asWedit + extensions//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.0 Level 1//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.0 Level 2//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.0 Strict Level 1//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.0 Strict Level 2//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.0 Strict//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.0//"),
    GUMBO_STRING("-//IETF//DTD HTML 2.1E//"),
    GUMBO_STRING("-//IETF//DTD HTML 3.0//"),
    GUMBO_STRING("-//IETF//DTD HTML 3.2 Final//"),
    GUMBO_STRING("-//IETF//DTD HTML 3.2//"),
    GUMBO_STRING("-//IETF//DTD HTML 3//"),
    GUMBO_STRING("-//IETF//DTD HTML Level 0//"),
    GUMBO_STRING("-//IETF//DTD HTML Level 1//"),
    GUMBO_STRING("-//IETF//DTD HTML Level 2//"),
    GUMBO_STRING("-//IETF//DTD HTML Level 3//"),
    GUMBO_STRING("-//IETF//DTD HTML Strict Level 0//"),
    GUMBO_STRING("-//IETF//DTD HTML Strict Level 1//"),
    GUMBO_STRING("-//IETF//DTD HTML Strict Level 2//"),
    GUMBO_STRING("-//IETF//DTD HTML Strict Level 3//"),
    GUMBO_STRING("-//IETF//DTD HTML Strict//"),
    GUMBO_STRING("-//IETF//DTD HTML//"),
    GUMBO_STRING("-//Metrius//DTD Metrius Presentational//"),
    GUMBO_STRING("-//Microsoft//DTD Internet Explorer 2.0 HTML Strict//"),
    GUMBO_STRING("-//Microsoft//DTD Internet Explorer 2.0 HTML//"),
    GUMBO_STRING("-//Microsoft//DTD Internet Explorer 2.0 Tables//"),
    GUMBO_STRING("-//Microsoft//DTD Internet Explorer 3.0 HTML Strict//"),
    GUMBO_STRING("-//Microsoft//DTD Internet Explorer 3.0 HTML//"),
    GUMBO_STRING("-//Microsoft//DTD Internet Explorer 3.0 Tables//"),
    GUMBO_STRING("-//Netscape Comm. Corp.//DTD HTML//"),
    GUMBO_STRING("-//Netscape Comm. Corp.//DTD Strict HTML//"),
    GUMBO_STRING("-//O'Reilly and Associates//DTD HTML 2.0//"),
    GUMBO_STRING("-//O'Reilly and Associates//DTD HTML Extended 1.0//"),
    GUMBO_STRING("-//O'Reilly and Associates//DTD HTML Extended Relaxed 1.0//"),
    GUMBO_STRING(
        "-//SoftQuad Software//DTD HoTMetaL PRO 6.0::19990601::)"
        "extensions to HTML 4.0//"),
    GUMBO_STRING(
        "-//SoftQuad//DTD HoTMetaL PRO 4.0::19971010::"
        "extensions to HTML 4.0//"),
    GUMBO_STRING("-//Spyglass//DTD HTML 2.0 Extended//"),
    GUMBO_STRING("-//SQ//DTD HTML 2.0 HoTMetaL + extensions//"),
    GUMBO_STRING("-//Sun Microsystems Corp.//DTD HotJava HTML//"),
    GUMBO_STRING("-//Sun Microsystems Corp.//DTD HotJava Strict HTML//"),
    GUMBO_STRING("-//W3C//DTD HTML 3 1995-03-24//"),
    GUMBO_STRING("-//W3C//DTD HTML 3.2 Draft//"),
    GUMBO_STRING("-//W3C//DTD HTML 3.2 Final//"),
    GUMBO_STRING("-//W3C//DTD HTML 3.2//"),
    GUMBO_STRING("-//W3C//DTD HTML 3.2S Draft//"),
    GUMBO_STRING("-//W3C//DTD HTML 4.0 Frameset//"),
    GUMBO_STRING("-//W3C//DTD HTML 4.0 Transitional//"),
    GUMBO_STRING("-//W3C//DTD HTML Experimental 19960712//"),
    GUMBO_STRING("-//W3C//DTD HTML Experimental 970421//"),
    GUMBO_STRING("-//W3C//DTD W3 HTML//"),
    GUMBO_STRING("-//W3O//DTD W3 HTML 3.0//"),
    GUMBO_STRING("-//WebTechs//DTD Mozilla HTML 2.0//"),
    GUMBO_STRING("-//WebTechs//DTD Mozilla HTML//"), TERMINATOR};

static const GumboStringPiece kQuirksModePublicIdExactMatches[] = {
    GUMBO_STRING("-//W3O//DTD W3 HTML Strict 3.0//EN//"),
    GUMBO_STRING("-/W3C/DTD HTML 4.0 Transitional/EN"), GUMBO_STRING("HTML"),
    TERMINATOR};

static const GumboStringPiece kQuirksModeSystemIdExactMatches[] = {
    GUMBO_STRING("http://www.ibm.com/data/dtd/v11/ibmxhtml1-transitional.dtd"),
    TERMINATOR};

static const GumboStringPiece kLimitedQuirksPublicIdPrefixes[] = {
    GUMBO_STRING("-//W3C//DTD XHTML 1.0 Frameset//"),
    GUMBO_STRING("-//W3C//DTD XHTML 1.0 Transitional//"), TERMINATOR};

static const GumboStringPiece kLimitedQuirksRequiresSystemIdPublicIdPrefixes[] =
    {GUMBO_STRING("-//W3C//DTD HTML 4.01 Frameset//"),
        GUMBO_STRING("-//W3C//DTD HTML 4.01 Transitional//"), TERMINATOR};

static const char* kLegalXmlns[] = {"http://www.w3.org/1999/xhtml",
    "http://www.w3.org/2000/svg", "http://www.w3.org/1998/Math/MathML"};

typedef struct _ReplacementEntry {
  const GumboStringPiece from;
  const GumboStringPiece to;
} ReplacementEntry;

#define REPLACEMENT_ENTRY(from, to) \
  { GUMBO_STRING(from), GUMBO_STRING(to) }

static const ReplacementEntry kSvgAttributeReplacements[] = {
    REPLACEMENT_ENTRY("attributename", "attributeName"),
    REPLACEMENT_ENTRY("attributetype", "attributeType"),
    REPLACEMENT_ENTRY("basefrequency", "baseFrequency"),
    REPLACEMENT_ENTRY("baseprofile", "baseProfile"),
    REPLACEMENT_ENTRY("calcmode", "calcMode"),
    REPLACEMENT_ENTRY("clippathunits", "clipPathUnits"),
    REPLACEMENT_ENTRY("diffuseconstant", "diffuseConstant"),
    REPLACEMENT_ENTRY("edgemode", "edgeMode"),
    REPLACEMENT_ENTRY("filterunits", "filterUnits"),
    REPLACEMENT_ENTRY("glyphref", "glyphRef"),
    REPLACEMENT_ENTRY("gradienttransform", "gradientTransform"),
    REPLACEMENT_ENTRY("gradientunits", "gradientUnits"),
    REPLACEMENT_ENTRY("kernelmatrix", "kernelMatrix"),
    REPLACEMENT_ENTRY("kernelunitlength", "kernelUnitLength"),
    REPLACEMENT_ENTRY("keypoints", "keyPoints"),
    REPLACEMENT_ENTRY("keysplines", "keySplines"),
    REPLACEMENT_ENTRY("keytimes", "keyTimes"),
    REPLACEMENT_ENTRY("lengthadjust", "lengthAdjust"),
    REPLACEMENT_ENTRY("limitingconeangle", "limitingConeAngle"),
    REPLACEMENT_ENTRY("markerheight", "markerHeight"),
    REPLACEMENT_ENTRY("markerunits", "markerUnits"),
    REPLACEMENT_ENTRY("markerwidth", "markerWidth"),
    REPLACEMENT_ENTRY("maskcontentunits", "maskContentUnits"),
    REPLACEMENT_ENTRY("maskunits", "maskUnits"),
    REPLACEMENT_ENTRY("numoctaves", "numOctaves"),
    REPLACEMENT_ENTRY("pathlength", "pathLength"),
    REPLACEMENT_ENTRY("patterncontentunits", "patternContentUnits"),
    REPLACEMENT_ENTRY("patterntransform", "patternTransform"),
    REPLACEMENT_ENTRY("patternunits", "patternUnits"),
    REPLACEMENT_ENTRY("pointsatx", "pointsAtX"),
    REPLACEMENT_ENTRY("pointsaty", "pointsAtY"),
    REPLACEMENT_ENTRY("pointsatz", "pointsAtZ"),
    REPLACEMENT_ENTRY("preservealpha", "preserveAlpha"),
    REPLACEMENT_ENTRY("preserveaspectratio", "preserveAspectRatio"),
    REPLACEMENT_ENTRY("primitiveunits", "primitiveUnits"),
    REPLACEMENT_ENTRY("refx", "refX"), REPLACEMENT_ENTRY("refy", "refY"),
    REPLACEMENT_ENTRY("repeatcount", "repeatCount"),
    REPLACEMENT_ENTRY("repeatdur", "repeatDur"),
    REPLACEMENT_ENTRY("requiredextensions", "requiredExtensions"),
    REPLACEMENT_ENTRY("requiredfeatures", "requiredFeatures"),
    REPLACEMENT_ENTRY("specularconstant", "specularConstant"),
    REPLACEMENT_ENTRY("specularexponent", "specularExponent"),
    REPLACEMENT_ENTRY("spreadmethod", "spreadMethod"),
    REPLACEMENT_ENTRY("startoffset", "startOffset"),
    REPLACEMENT_ENTRY("stddeviation", "stdDeviation"),
    REPLACEMENT_ENTRY("stitchtiles", "stitchTiles"),
    REPLACEMENT_ENTRY("surfacescale", "surfaceScale"),
    REPLACEMENT_ENTRY("systemlanguage", "systemLanguage"),
    REPLACEMENT_ENTRY("tablevalues", "tableValues"),
    REPLACEMENT_ENTRY("targetx", "targetX"),
    REPLACEMENT_ENTRY("targety", "targetY"),
    REPLACEMENT_ENTRY("textlength", "textLength"),
    REPLACEMENT_ENTRY("viewbox", "viewBox"),
    REPLACEMENT_ENTRY("viewtarget", "viewTarget"),
    REPLACEMENT_ENTRY("xchannelselector", "xChannelSelector"),
    REPLACEMENT_ENTRY("ychannelselector", "yChannelSelector"),
    REPLACEMENT_ENTRY("zoomandpan", "zoomAndPan"),
};

static const ReplacementEntry kSvgTagReplacements[] = {
    REPLACEMENT_ENTRY("altglyph", "altGlyph"),
    REPLACEMENT_ENTRY("altglyphdef", "altGlyphDef"),
    REPLACEMENT_ENTRY("altglyphitem", "altGlyphItem"),
    REPLACEMENT_ENTRY("animatecolor", "animateColor"),
    REPLACEMENT_ENTRY("animatemotion", "animateMotion"),
    REPLACEMENT_ENTRY("animatetransform", "animateTransform"),
    REPLACEMENT_ENTRY("clippath", "clipPath"),
    REPLACEMENT_ENTRY("feblend", "feBlend"),
    REPLACEMENT_ENTRY("fecolormatrix", "feColorMatrix"),
    REPLACEMENT_ENTRY("fecomponenttransfer", "feComponentTransfer"),
    REPLACEMENT_ENTRY("fecomposite", "feComposite"),
    REPLACEMENT_ENTRY("feconvolvematrix", "feConvolveMatrix"),
    REPLACEMENT_ENTRY("fediffuselighting", "feDiffuseLighting"),
    REPLACEMENT_ENTRY("fedisplacementmap", "feDisplacementMap"),
    REPLACEMENT_ENTRY("fedistantlight", "feDistantLight"),
    REPLACEMENT_ENTRY("feflood", "feFlood"),
    REPLACEMENT_ENTRY("fefunca", "feFuncA"),
    REPLACEMENT_ENTRY("fefuncb", "feFuncB"),
    REPLACEMENT_ENTRY("fefuncg", "feFuncG"),
    REPLACEMENT_ENTRY("fefuncr", "feFuncR"),
    REPLACEMENT_ENTRY("fegaussianblur", "feGaussianBlur"),
    REPLACEMENT_ENTRY("feimage", "feImage"),
    REPLACEMENT_ENTRY("femerge", "feMerge"),
    REPLACEMENT_ENTRY("femergenode", "feMergeNode"),
    REPLACEMENT_ENTRY("femorphology", "feMorphology"),
    REPLACEMENT_ENTRY("feoffset", "feOffset"),
    REPLACEMENT_ENTRY("fepointlight", "fePointLight"),
    REPLACEMENT_ENTRY("fespecularlighting", "feSpecularLighting"),
    REPLACEMENT_ENTRY("fespotlight", "feSpotLight"),
    REPLACEMENT_ENTRY("fetile", "feTile"),
    REPLACEMENT_ENTRY("feturbulence", "feTurbulence"),
    REPLACEMENT_ENTRY("foreignobject", "foreignObject"),
    REPLACEMENT_ENTRY("glyphref", "glyphRef"),
    REPLACEMENT_ENTRY("lineargradient", "linearGradient"),
    REPLACEMENT_ENTRY("radialgradient", "radialGradient"),
    REPLACEMENT_ENTRY("textpath", "textPath"),
};

typedef struct _NamespacedAttributeReplacement {
  const char* from;
  const char* local_name;
  const GumboAttributeNamespaceEnum attr_namespace;
} NamespacedAttributeReplacement;

static const NamespacedAttributeReplacement kForeignAttributeReplacements[] = {
    {"xlink:actuate", "actuate", GUMBO_ATTR_NAMESPACE_XLINK},
    {"xlink:arcrole", "arcrole", GUMBO_ATTR_NAMESPACE_XLINK},
    {"xlink:href",    "href",    GUMBO_ATTR_NAMESPACE_XLINK},
    {"xlink:role",    "role",    GUMBO_ATTR_NAMESPACE_XLINK},
    {"xlink:show",    "show",    GUMBO_ATTR_NAMESPACE_XLINK},
    {"xlink:title",   "title",   GUMBO_ATTR_NAMESPACE_XLINK},
    {"xlink:type",    "type",    GUMBO_ATTR_NAMESPACE_XLINK},
    {"xml:lang",      "lang",    GUMBO_ATTR_NAMESPACE_XML},
    {"xml:space",     "space",   GUMBO_ATTR_NAMESPACE_XML},
    {"xmlns",         "xmlns",   GUMBO_ATTR_NAMESPACE_XMLNS},
    {"xmlns:xlink",   "xlink",   GUMBO_ATTR_NAMESPACE_XMLNS},
};

static const GumboNode kActiveFormattingScopeMarker;

static const bool kStartTag = true;
static const bool kEndTag = false;

typedef struct _TextNodeBufferState {
  GumboStringBuffer _buffer;

  const char* _start_original_text;

  GumboSourcePosition _start_position;

  GumboNodeType _type;
} TextNodeBufferState;

typedef struct GumboInternalParserState {
  GumboInsertionMode _insertion_mode;

  GumboInsertionMode _original_insertion_mode;

  GumboVector   _open_elements;

  GumboVector   _active_formatting_elements;

  GumboVector   _template_insertion_modes;

  GumboNode* _head_element;
  GumboNode* _form_element;

  GumboNode* _fragment_ctx;

  bool _reprocess_current_token;

  bool _self_closing_flag_acknowledged;

  bool _frameset_ok;

  bool _ignore_next_linefeed;

  bool _foster_parent_insertions;

  TextNodeBufferState _text_node;

  GumboToken* _current_token;

  bool _closed_body_tag;
  bool _closed_html_tag;
} GumboParserState;

static bool token_has_attribute(const GumboToken* token, const char* name) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  return gumbo_get_attribute(&token->v.start_tag.attributes, name) != NULL;
}

static bool attribute_matches(
    const GumboVector* attributes, const char* name, const char* value) {
  const GumboAttribute* attr = gumbo_get_attribute(attributes, name);
  return attr ? strcasecmp(value, attr->value) == 0 : false;
}

static bool attribute_matches_case_sensitive(
    const GumboVector* attributes, const char* name, const char* value) {
  const GumboAttribute* attr = gumbo_get_attribute(attributes, name);
  return attr ? strcmp(value, attr->value) == 0 : false;
}

static bool all_attributes_match(
    const GumboVector* attr1, const GumboVector* attr2) {
  unsigned int num_unmatched_attr2_elements = attr2->length;
  for (unsigned int i = 0; i < attr1->length; ++i) {
    const GumboAttribute* attr = attr1->data[i];
    if (attribute_matches_case_sensitive(attr2, attr->name, attr->value)) {
      --num_unmatched_attr2_elements;
    } else {
      return false;
    }
  }
  return num_unmatched_attr2_elements == 0;
}

static void set_frameset_not_ok(GumboParser* parser) {
  gumbo_debug("Setting frameset_ok to false.\n");
  parser->_parser_state->_frameset_ok = false;
}

static GumboNode* create_node(GumboParser* parser, GumboNodeType type) {
  GumboNode* node = gumbo_parser_allocate(parser, sizeof(GumboNode));
  node->parent = NULL;
  node->index_within_parent = -1;
  node->type = type;
  node->parse_flags = GUMBO_INSERTION_NORMAL;
  return node;
}

static GumboNode* new_document_node(GumboParser* parser) {
  GumboNode* document_node = create_node(parser, GUMBO_NODE_DOCUMENT);
  document_node->parse_flags = GUMBO_INSERTION_BY_PARSER;
  gumbo_vector_init(parser, 1, &document_node->v.document.children);

  GumboDocument* document = &document_node->v.document;
  document->has_doctype = false;
  document->name = NULL;
  document->public_identifier = NULL;
  document->system_identifier = NULL;
  return document_node;
}

static void output_init(GumboParser* parser) {
  GumboOutput* output = gumbo_parser_allocate(parser, sizeof(GumboOutput));
  output->root = NULL;
  output->document = new_document_node(parser);
  parser->_output = output;
  gumbo_init_errors(parser);
}

static void parser_state_init(GumboParser* parser) {
  GumboParserState* parser_state =
      gumbo_parser_allocate(parser, sizeof(GumboParserState));
  parser_state->_insertion_mode = GUMBO_INSERTION_MODE_INITIAL;
  parser_state->_reprocess_current_token = false;
  parser_state->_frameset_ok = true;
  parser_state->_ignore_next_linefeed = false;
  parser_state->_foster_parent_insertions = false;
  parser_state->_text_node._type = GUMBO_NODE_WHITESPACE;
  gumbo_string_buffer_init(parser, &parser_state->_text_node._buffer);
  gumbo_vector_init(parser, 10, &parser_state->_open_elements);
  gumbo_vector_init(parser, 5, &parser_state->_active_formatting_elements);
  gumbo_vector_init(parser, 5, &parser_state->_template_insertion_modes);
  parser_state->_head_element = NULL;
  parser_state->_form_element = NULL;
  parser_state->_fragment_ctx = NULL;
  parser_state->_current_token = NULL;
  parser_state->_closed_body_tag = false;
  parser_state->_closed_html_tag = false;
  parser->_parser_state = parser_state;
}

static void parser_state_destroy(GumboParser* parser) {
  GumboParserState* state = parser->_parser_state;
  if (state->_fragment_ctx) {
    destroy_node(parser, state->_fragment_ctx);
  }
  gumbo_vector_destroy(parser, &state->_active_formatting_elements);
  gumbo_vector_destroy(parser, &state->_open_elements);
  gumbo_vector_destroy(parser, &state->_template_insertion_modes);
  gumbo_string_buffer_destroy(parser, &state->_text_node._buffer);
  gumbo_parser_deallocate(parser, state);
}

static GumboNode* get_document_node(GumboParser* parser) {
  return parser->_output->document;
}

static bool is_fragment_parser(const GumboParser* parser) {
  return !!parser->_parser_state->_fragment_ctx;
}

static GumboNode* get_current_node(GumboParser* parser) {
  GumboVector* open_elements = &parser->_parser_state->_open_elements;
  if (open_elements->length == 0) {
    assert(!parser->_output->root);
    return NULL;
  }
  assert(open_elements->length > 0);
  assert(open_elements->data != NULL);
  return open_elements->data[open_elements->length - 1];
}

static GumboNode* get_adjusted_current_node(GumboParser* parser) {
  GumboParserState* state = parser->_parser_state;
  if (state->_open_elements.length == 1 && state->_fragment_ctx) {
    return state->_fragment_ctx;
  }
  return get_current_node(parser);
}

static bool is_in_static_list(
    const char* needle, const GumboStringPiece* haystack, bool exact_match) {
  for (unsigned int i = 0; haystack[i].length > 0; ++i) {
    if ((exact_match && !strcmp(needle, haystack[i].data)) ||
        (!exact_match && !strcasecmp(needle, haystack[i].data))) {
      return true;
    }
  }
  return false;
}

static void set_insertion_mode(GumboParser* parser, GumboInsertionMode mode) {
  parser->_parser_state->_insertion_mode = mode;
}

static GumboInsertionMode get_appropriate_insertion_mode(
    const GumboParser* parser, int index) {
  const GumboVector* open_elements = &parser->_parser_state->_open_elements;
  const GumboNode* node = open_elements->data[index];
  const bool is_last = index == 0;

  if (is_last && is_fragment_parser(parser)) {
    node = parser->_parser_state->_fragment_ctx;
  }

  assert(node->type == GUMBO_NODE_ELEMENT || node->type == GUMBO_NODE_TEMPLATE);
  if (node->v.element.tag_namespace != GUMBO_NAMESPACE_HTML)
    return is_last ?
      GUMBO_INSERTION_MODE_IN_BODY : GUMBO_INSERTION_MODE_INITIAL;

  switch (node->v.element.tag) {
    case GUMBO_TAG_SELECT: {
      if (is_last) {
        return GUMBO_INSERTION_MODE_IN_SELECT;
      }
      for (int i = index; i > 0; --i) {
        const GumboNode* ancestor = open_elements->data[i];
        if (node_html_tag_is(ancestor, GUMBO_TAG_TEMPLATE)) {
          return GUMBO_INSERTION_MODE_IN_SELECT;
        }
        if (node_html_tag_is(ancestor, GUMBO_TAG_TABLE)) {
          return GUMBO_INSERTION_MODE_IN_SELECT_IN_TABLE;
        }
      }
      return GUMBO_INSERTION_MODE_IN_SELECT;
    }
    case GUMBO_TAG_TD:
    case GUMBO_TAG_TH:
      if (!is_last) return GUMBO_INSERTION_MODE_IN_CELL;
      break;
    case GUMBO_TAG_TR:
      return GUMBO_INSERTION_MODE_IN_ROW;
    case GUMBO_TAG_TBODY:
    case GUMBO_TAG_THEAD:
    case GUMBO_TAG_TFOOT:
      return GUMBO_INSERTION_MODE_IN_TABLE_BODY;
    case GUMBO_TAG_CAPTION:
      return GUMBO_INSERTION_MODE_IN_CAPTION;
    case GUMBO_TAG_COLGROUP:
      return GUMBO_INSERTION_MODE_IN_COLUMN_GROUP;
    case GUMBO_TAG_TABLE:
      return GUMBO_INSERTION_MODE_IN_TABLE;
    case GUMBO_TAG_TEMPLATE:
      return get_current_template_insertion_mode(parser);
    case GUMBO_TAG_HEAD:
      if (!is_last) return GUMBO_INSERTION_MODE_IN_HEAD;
      break;
    case GUMBO_TAG_BODY:
      return GUMBO_INSERTION_MODE_IN_BODY;
    case GUMBO_TAG_FRAMESET:
      return GUMBO_INSERTION_MODE_IN_FRAMESET;
    case GUMBO_TAG_HTML:
      return parser->_parser_state->_head_element
                 ? GUMBO_INSERTION_MODE_AFTER_HEAD
                 : GUMBO_INSERTION_MODE_BEFORE_HEAD;
    default:
      break;
  }
  return is_last ? GUMBO_INSERTION_MODE_IN_BODY : GUMBO_INSERTION_MODE_INITIAL;
}

static void reset_insertion_mode_appropriately(GumboParser* parser) {
  const GumboVector* open_elements = &parser->_parser_state->_open_elements;
  for (int i = open_elements->length; --i >= 0;) {
    GumboInsertionMode mode = get_appropriate_insertion_mode(parser, i);
    if (mode != GUMBO_INSERTION_MODE_INITIAL) {
      set_insertion_mode(parser, mode);
      return;
    }
  }
  assert(0);
}

static GumboError* parser_add_parse_error(
    GumboParser* parser, const GumboToken* token) {
  gumbo_debug("Adding parse error.\n");
  GumboError* error = gumbo_add_error(parser);
  if (!error) {
    return NULL;
  }
  error->type = GUMBO_ERR_PARSER;
  error->position = token->position;
  error->original_text = token->original_text.data;
  GumboParserError* extra_data = &error->v.parser;
  extra_data->input_type = token->type;
  extra_data->input_tag = GUMBO_TAG_UNKNOWN;
  if (token->type == GUMBO_TOKEN_START_TAG) {
    extra_data->input_tag = token->v.start_tag.tag;
  } else if (token->type == GUMBO_TOKEN_END_TAG) {
    extra_data->input_tag = token->v.end_tag;
  }
  GumboParserState* state = parser->_parser_state;
  extra_data->parser_state = state->_insertion_mode;
  gumbo_vector_init(
      parser, state->_open_elements.length, &extra_data->tag_stack);
  for (unsigned int i = 0; i < state->_open_elements.length; ++i) {
    const GumboNode* node = state->_open_elements.data[i];
    assert(
        node->type == GUMBO_NODE_ELEMENT || node->type == GUMBO_NODE_TEMPLATE);
    gumbo_vector_add(
        parser, (void*) node->v.element.tag, &extra_data->tag_stack);
  }
  return error;
}

static bool tag_in(
    const GumboToken* token, bool is_start, const gumbo_tagset tags) {
  GumboTag token_tag;
  if (is_start && token->type == GUMBO_TOKEN_START_TAG) {
    token_tag = token->v.start_tag.tag;
  } else if (!is_start && token->type == GUMBO_TOKEN_END_TAG) {
    token_tag = token->v.end_tag;
  } else {
    return false;
  }
  return (token_tag < GUMBO_TAG_LAST && tags[(int) token_tag] != 0);
}

static bool tag_is(const GumboToken* token, bool is_start, GumboTag tag) {
  if (is_start && token->type == GUMBO_TOKEN_START_TAG) {
    return token->v.start_tag.tag == tag;
  } else if (!is_start && token->type == GUMBO_TOKEN_END_TAG) {
    return token->v.end_tag == tag;
  } else {
    return false;
  }
}

static bool node_tag_in_set(const GumboNode* node, const gumbo_tagset tags) {
  assert(node != NULL);
  if (node->type != GUMBO_NODE_ELEMENT && node->type != GUMBO_NODE_TEMPLATE) {
    return false;
  }
  return TAGSET_INCLUDES(
      tags, node->v.element.tag_namespace, node->v.element.tag);
}

static bool node_qualified_tag_is(
    const GumboNode* node, GumboNamespaceEnum ns, GumboTag tag) {
  assert(node);
  return (node->type == GUMBO_NODE_ELEMENT ||
             node->type == GUMBO_NODE_TEMPLATE) &&
         node->v.element.tag == tag && node->v.element.tag_namespace == ns;
}

static bool node_html_tag_is(const GumboNode* node, GumboTag tag) {
  return node_qualified_tag_is(node, GUMBO_NAMESPACE_HTML, tag);
}

static void push_template_insertion_mode(
    GumboParser* parser, GumboInsertionMode mode) {
  gumbo_vector_add(
      parser, (void*) mode, &parser->_parser_state->_template_insertion_modes);
}

static void pop_template_insertion_mode(GumboParser* parser) {
  gumbo_vector_pop(parser, &parser->_parser_state->_template_insertion_modes);
}

static GumboInsertionMode get_current_template_insertion_mode(
    const GumboParser* parser) {
  GumboVector* template_insertion_modes =
      &parser->_parser_state->_template_insertion_modes;
  if (template_insertion_modes->length == 0) {
    return GUMBO_INSERTION_MODE_INITIAL;
  }
  return (GumboInsertionMode)
      template_insertion_modes->data[(template_insertion_modes->length - 1)];
}

static bool is_mathml_integration_point(const GumboNode* node) {
  return node_tag_in_set(
      node, (gumbo_tagset){TAG_MATHML(MI), TAG_MATHML(MO), TAG_MATHML(MN),
                TAG_MATHML(MS), TAG_MATHML(MTEXT)});
}

static bool is_html_integration_point(const GumboNode* node) {
  return node_tag_in_set(node, (gumbo_tagset){TAG_SVG(FOREIGNOBJECT),
                                   TAG_SVG(DESC), TAG_SVG(TITLE)}) ||
         (node_qualified_tag_is(
              node, GUMBO_NAMESPACE_MATHML, GUMBO_TAG_ANNOTATION_XML) &&
             (attribute_matches(
                  &node->v.element.attributes, "encoding", "text/html") ||
                 attribute_matches(&node->v.element.attributes, "encoding",
                     "application/xhtml+xml")));
}

typedef struct {
  GumboNode* target;
  int index;
} InsertionLocation;

InsertionLocation get_appropriate_insertion_location(
    GumboParser* parser, GumboNode* override_target) {
  InsertionLocation retval = {override_target, -1};
  if (retval.target == NULL) {
    retval.target = parser->_output->root != NULL ? get_current_node(parser)
                                                  : get_document_node(parser);
  }
  if (!parser->_parser_state->_foster_parent_insertions ||
      !node_tag_in_set(retval.target, (gumbo_tagset){TAG(TABLE), TAG(TBODY),
                                          TAG(TFOOT), TAG(THEAD), TAG(TR)})) {
    return retval;
  }

  int last_template_index = -1;
  int last_table_index = -1;
  GumboVector* open_elements = &parser->_parser_state->_open_elements;
  for (unsigned int i = 0; i < open_elements->length; ++i) {
    if (node_html_tag_is(open_elements->data[i], GUMBO_TAG_TEMPLATE)) {
      last_template_index = i;
    }
    if (node_html_tag_is(open_elements->data[i], GUMBO_TAG_TABLE)) {
      last_table_index = i;
    }
  }
  if (last_template_index != -1 &&
      (last_table_index == -1 || last_template_index > last_table_index)) {
    retval.target = open_elements->data[last_template_index];
    return retval;
  }
  if (last_table_index == -1) {
    retval.target = open_elements->data[0];
    return retval;
  }
  GumboNode* last_table = open_elements->data[last_table_index];
  if (last_table->parent != NULL) {
    retval.target = last_table->parent;
    retval.index = last_table->index_within_parent;
    return retval;
  }

  retval.target = open_elements->data[last_table_index - 1];
  return retval;
}

static void append_node(
    GumboParser* parser, GumboNode* parent, GumboNode* node) {
  assert(node->parent == NULL);
  assert(node->index_within_parent == -1);
  GumboVector* children;
  if (parent->type == GUMBO_NODE_ELEMENT ||
      parent->type == GUMBO_NODE_TEMPLATE) {
    children = &parent->v.element.children;
  } else {
    assert(parent->type == GUMBO_NODE_DOCUMENT);
    children = &parent->v.document.children;
  }
  node->parent = parent;
  node->index_within_parent = children->length;
  gumbo_vector_add(parser, (void*) node, children);
  assert(node->index_within_parent < children->length);
}

static void insert_node(
    GumboParser* parser, GumboNode* node, InsertionLocation location) {
  assert(node->parent == NULL);
  assert(node->index_within_parent == -1);
  GumboNode* parent = location.target;
  int index = location.index;
  if (index != -1) {
    GumboVector* children = NULL;
    if (parent->type == GUMBO_NODE_ELEMENT ||
        parent->type == GUMBO_NODE_TEMPLATE) {
      children = &parent->v.element.children;
    } else if (parent->type == GUMBO_NODE_DOCUMENT) {
      children = &parent->v.document.children;
      assert(children->length == 0);
    } else {
      assert(0);
    }

    assert(index >= 0);
    assert((unsigned int) index < children->length);
    node->parent = parent;
    node->index_within_parent = index;
    gumbo_vector_insert_at(parser, (void*) node, index, children);
    assert(node->index_within_parent < children->length);
    for (unsigned int i = index + 1; i < children->length; ++i) {
      GumboNode* sibling = children->data[i];
      sibling->index_within_parent = i;
      assert(sibling->index_within_parent < children->length);
    }
  } else {
    append_node(parser, parent, node);
  }
}

static void maybe_flush_text_node_buffer(GumboParser* parser) {
  GumboParserState* state = parser->_parser_state;
  TextNodeBufferState* buffer_state = &state->_text_node;
  if (buffer_state->_buffer.length == 0) {
    return;
  }

  assert(buffer_state->_type == GUMBO_NODE_WHITESPACE ||
         buffer_state->_type == GUMBO_NODE_TEXT ||
         buffer_state->_type == GUMBO_NODE_CDATA);
  GumboNode* text_node = create_node(parser, buffer_state->_type);
  GumboText* text_node_data = &text_node->v.text;
  text_node_data->text =
      gumbo_string_buffer_to_string(parser, &buffer_state->_buffer);
  text_node_data->original_text.data = buffer_state->_start_original_text;
  text_node_data->original_text.length =
      state->_current_token->original_text.data -
      buffer_state->_start_original_text;
  text_node_data->start_pos = buffer_state->_start_position;

  gumbo_debug("Flushing text node buffer of %.*s.\n",
      (int) buffer_state->_buffer.length, buffer_state->_buffer.data);

  InsertionLocation location = get_appropriate_insertion_location(parser, NULL);
  if (location.target->type == GUMBO_NODE_DOCUMENT) {
    destroy_node(parser, text_node);
  } else {
    insert_node(parser, text_node, location);
  }

  gumbo_string_buffer_clear(parser, &buffer_state->_buffer);
  buffer_state->_type = GUMBO_NODE_WHITESPACE;
  assert(buffer_state->_buffer.length == 0);
}

static void record_end_of_element(
    GumboToken* current_token, GumboElement* element) {
  element->end_pos = current_token->position;
  element->original_end_tag = current_token->type == GUMBO_TOKEN_END_TAG
                                  ? current_token->original_text
                                  : kGumboEmptyString;
}

static GumboNode* pop_current_node(GumboParser* parser) {
  GumboParserState* state = parser->_parser_state;
  maybe_flush_text_node_buffer(parser);
  if (state->_open_elements.length > 0) {
    assert(node_html_tag_is(state->_open_elements.data[0], GUMBO_TAG_HTML));
    gumbo_debug("Popping %s node.\n",
        gumbo_normalized_tagname(get_current_node(parser)->v.element.tag));
  }
  GumboNode* current_node = gumbo_vector_pop(parser, &state->_open_elements);
  if (!current_node) {
    assert(state->_open_elements.length == 0);
    return NULL;
  }
  assert(current_node->type == GUMBO_NODE_ELEMENT ||
         current_node->type == GUMBO_NODE_TEMPLATE);
  bool is_closed_body_or_html_tag =
      (node_html_tag_is(current_node, GUMBO_TAG_BODY) &&
          state->_closed_body_tag) ||
      (node_html_tag_is(current_node, GUMBO_TAG_HTML) &&
          state->_closed_html_tag);
  if ((state->_current_token->type != GUMBO_TOKEN_END_TAG ||
          !node_html_tag_is(current_node, state->_current_token->v.end_tag)) &&
      !is_closed_body_or_html_tag) {
    current_node->parse_flags |= GUMBO_INSERTION_IMPLICIT_END_TAG;
  }
  if (!is_closed_body_or_html_tag) {
    record_end_of_element(state->_current_token, &current_node->v.element);
  }
  return current_node;
}

static void append_comment_node(
    GumboParser* parser, GumboNode* node, const GumboToken* token) {
  maybe_flush_text_node_buffer(parser);
  GumboNode* comment = create_node(parser, GUMBO_NODE_COMMENT);
  comment->type = GUMBO_NODE_COMMENT;
  comment->parse_flags = GUMBO_INSERTION_NORMAL;
  comment->v.text.text = token->v.text;
  comment->v.text.original_text = token->original_text;
  comment->v.text.start_pos = token->position;
  append_node(parser, node, comment);
}

static void clear_stack_to_table_row_context(GumboParser* parser) {
  while (!node_tag_in_set(get_current_node(parser),
             (gumbo_tagset){TAG(HTML), TAG(TR), TAG(TEMPLATE)})) {
    pop_current_node(parser);
  }
}

static void clear_stack_to_table_context(GumboParser* parser) {
  while (!node_tag_in_set(get_current_node(parser),
             (gumbo_tagset){TAG(HTML), TAG(TABLE), TAG(TEMPLATE)})) {
    pop_current_node(parser);
  }
}

void clear_stack_to_table_body_context(GumboParser* parser) {
  while (!node_tag_in_set(get_current_node(parser),
             (gumbo_tagset){TAG(HTML), TAG(TBODY), TAG(TFOOT), TAG(THEAD),
                 TAG(TEMPLATE)})) {
    pop_current_node(parser);
  }
}

static GumboNode* create_element(GumboParser* parser, GumboTag tag) {
  GumboNode* node = create_node(parser, GUMBO_NODE_ELEMENT);
  GumboElement* element = &node->v.element;
  gumbo_vector_init(parser, 1, &element->children);
  gumbo_vector_init(parser, 0, &element->attributes);
  element->tag = tag;
  element->tag_namespace = GUMBO_NAMESPACE_HTML;
  element->original_tag = kGumboEmptyString;
  element->original_end_tag = kGumboEmptyString;
  element->start_pos = (parser->_parser_state->_current_token)
                           ? parser->_parser_state->_current_token->position
                           : kGumboEmptySourcePosition;
  element->end_pos = kGumboEmptySourcePosition;
  return node;
}

static GumboNode* create_element_from_token(
    GumboParser* parser, GumboToken* token, GumboNamespaceEnum tag_namespace) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  GumboTokenStartTag* start_tag = &token->v.start_tag;

  GumboNodeType type = (tag_namespace == GUMBO_NAMESPACE_HTML &&
                           start_tag->tag == GUMBO_TAG_TEMPLATE)
                           ? GUMBO_NODE_TEMPLATE
                           : GUMBO_NODE_ELEMENT;

  GumboNode* node = create_node(parser, type);
  GumboElement* element = &node->v.element;
  gumbo_vector_init(parser, 1, &element->children);
  element->attributes = start_tag->attributes;
  element->tag = start_tag->tag;
  element->tag_namespace = tag_namespace;

  assert(token->original_text.length >= 2);
  assert(token->original_text.data[0] == '<');
  assert(token->original_text.data[token->original_text.length - 1] == '>');
  element->original_tag = token->original_text;
  element->start_pos = token->position;
  element->original_end_tag = kGumboEmptyString;
  element->end_pos = kGumboEmptySourcePosition;

  start_tag->attributes = kGumboEmptyVector;
  return node;
}

static void insert_element(GumboParser* parser, GumboNode* node,
    bool is_reconstructing_formatting_elements) {
  GumboParserState* state = parser->_parser_state;
  if (!is_reconstructing_formatting_elements) {
    maybe_flush_text_node_buffer(parser);
  }
  InsertionLocation location = get_appropriate_insertion_location(parser, NULL);
  insert_node(parser, node, location);
  gumbo_vector_add(parser, (void*) node, &state->_open_elements);
}

static GumboNode* insert_element_from_token(
    GumboParser* parser, GumboToken* token) {
  GumboNode* element =
      create_element_from_token(parser, token, GUMBO_NAMESPACE_HTML);
  insert_element(parser, element, false);
  gumbo_debug("Inserting <%s> element (@%x) from token.\n",
      gumbo_normalized_tagname(element->v.element.tag), element);
  return element;
}

static GumboNode* insert_element_of_tag_type(
    GumboParser* parser, GumboTag tag, GumboParseFlags reason) {
  GumboNode* element = create_element(parser, tag);
  element->parse_flags |= GUMBO_INSERTION_BY_PARSER | reason;
  insert_element(parser, element, false);
  gumbo_debug("Inserting %s element (@%x) from tag type.\n",
      gumbo_normalized_tagname(tag), element);
  return element;
}

static GumboNode* insert_foreign_element(
    GumboParser* parser, GumboToken* token, GumboNamespaceEnum tag_namespace) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  GumboNode* element = create_element_from_token(parser, token, tag_namespace);
  insert_element(parser, element, false);
  if (token_has_attribute(token, "xmlns") &&
      !attribute_matches_case_sensitive(&token->v.start_tag.attributes, "xmlns",
          kLegalXmlns[tag_namespace])) {
    parser_add_parse_error(parser, token);
  }
  if (token_has_attribute(token, "xmlns:xlink") &&
      !attribute_matches_case_sensitive(&token->v.start_tag.attributes,
          "xmlns:xlink", "http://www.w3.org/1999/xlink")) {
    parser_add_parse_error(parser, token);
  }
  return element;
}

static void insert_text_token(GumboParser* parser, GumboToken* token) {
  assert(token->type == GUMBO_TOKEN_WHITESPACE ||
         token->type == GUMBO_TOKEN_CHARACTER ||
         token->type == GUMBO_TOKEN_NULL || token->type == GUMBO_TOKEN_CDATA);
  TextNodeBufferState* buffer_state = &parser->_parser_state->_text_node;
  if (buffer_state->_buffer.length == 0) {
    buffer_state->_start_original_text = token->original_text.data;
    buffer_state->_start_position = token->position;
  }
  gumbo_string_buffer_append_codepoint(
      parser, token->v.character, &buffer_state->_buffer);
  if (token->type == GUMBO_TOKEN_CHARACTER) {
    buffer_state->_type = GUMBO_NODE_TEXT;
  } else if (token->type == GUMBO_TOKEN_CDATA) {
    buffer_state->_type = GUMBO_NODE_CDATA;
  }
  gumbo_debug("Inserting text token '%c'.\n", token->v.character);
}

static void run_generic_parsing_algorithm(
    GumboParser* parser, GumboToken* token, GumboTokenizerEnum lexer_state) {
  insert_element_from_token(parser, token);
  gumbo_tokenizer_set_state(parser, lexer_state);
  parser->_parser_state->_original_insertion_mode =
      parser->_parser_state->_insertion_mode;
  parser->_parser_state->_insertion_mode = GUMBO_INSERTION_MODE_TEXT;
}

static void acknowledge_self_closing_tag(GumboParser* parser) {
  parser->_parser_state->_self_closing_flag_acknowledged = true;
}

static bool find_last_anchor_index(GumboParser* parser, int* anchor_index) {
  GumboVector* elements = &parser->_parser_state->_active_formatting_elements;
  for (int i = elements->length; --i >= 0;) {
    GumboNode* node = elements->data[i];
    if (node == &kActiveFormattingScopeMarker) {
      return false;
    }
    if (node_html_tag_is(node, GUMBO_TAG_A)) {
      *anchor_index = i;
      return true;
    }
  }
  return false;
}

static int count_formatting_elements_of_tag(GumboParser* parser,
    const GumboNode* desired_node, int* earliest_matching_index) {
  const GumboElement* desired_element = &desired_node->v.element;
  GumboVector* elements = &parser->_parser_state->_active_formatting_elements;
  int num_identical_elements = 0;
  for (int i = elements->length; --i >= 0;) {
    GumboNode* node = elements->data[i];
    if (node == &kActiveFormattingScopeMarker) {
      break;
    }
    assert(node->type == GUMBO_NODE_ELEMENT);
    if (node_qualified_tag_is(
            node, desired_element->tag_namespace, desired_element->tag) &&
        all_attributes_match(
            &node->v.element.attributes, &desired_element->attributes)) {
      num_identical_elements++;
      *earliest_matching_index = i;
    }
  }
  return num_identical_elements;
}

static void add_formatting_element(GumboParser* parser, const GumboNode* node) {
  assert(node == &kActiveFormattingScopeMarker ||
         node->type == GUMBO_NODE_ELEMENT);
  GumboVector* elements = &parser->_parser_state->_active_formatting_elements;
  if (node == &kActiveFormattingScopeMarker) {
    gumbo_debug("Adding a scope marker.\n");
  } else {
    gumbo_debug("Adding a formatting element.\n");
  }

  int earliest_identical_element = elements->length;
  int num_identical_elements = count_formatting_elements_of_tag(
      parser, node, &earliest_identical_element);

  if (num_identical_elements >= 3) {
    gumbo_debug("Noah's ark clause: removing element at %d.\n",
        earliest_identical_element);
    gumbo_vector_remove_at(parser, earliest_identical_element, elements);
  }

  gumbo_vector_add(parser, (void*) node, elements);
}

static bool is_open_element(GumboParser* parser, const GumboNode* node) {
  GumboVector* open_elements = &parser->_parser_state->_open_elements;
  for (unsigned int i = 0; i < open_elements->length; ++i) {
    if (open_elements->data[i] == node) {
      return true;
    }
  }
  return false;
}

GumboNode* clone_node(
    GumboParser* parser, GumboNode* node, GumboParseFlags reason) {
  assert(node->type == GUMBO_NODE_ELEMENT || node->type == GUMBO_NODE_TEMPLATE);
  GumboNode* new_node = gumbo_parser_allocate(parser, sizeof(GumboNode));
  *new_node = *node;
  new_node->parent = NULL;
  new_node->index_within_parent = -1;
  new_node->parse_flags &= ~GUMBO_INSERTION_IMPLICIT_END_TAG;
  new_node->parse_flags |= reason | GUMBO_INSERTION_BY_PARSER;
  GumboElement* element = &new_node->v.element;
  gumbo_vector_init(parser, 1, &element->children);

  const GumboVector* old_attributes = &node->v.element.attributes;
  gumbo_vector_init(parser, old_attributes->length, &element->attributes);
  for (unsigned int i = 0; i < old_attributes->length; ++i) {
    const GumboAttribute* old_attr = old_attributes->data[i];
    GumboAttribute* attr =
        gumbo_parser_allocate(parser, sizeof(GumboAttribute));
    *attr = *old_attr;
    attr->name = gumbo_copy_stringz(parser, old_attr->name);
    attr->value = gumbo_copy_stringz(parser, old_attr->value);
    gumbo_vector_add(parser, attr, &element->attributes);
  }
  return new_node;
}

static void reconstruct_active_formatting_elements(GumboParser* parser) {
  GumboVector* elements = &parser->_parser_state->_active_formatting_elements;
  if (elements->length == 0) {
    return;
  }

  unsigned int i = elements->length - 1;
  GumboNode* element = elements->data[i];
  if (element == &kActiveFormattingScopeMarker ||
      is_open_element(parser, element)) {
    return;
  }

  do {
    if (i == 0) {
      i = -1;
      break;
    }
    element = elements->data[--i];
  } while (element != &kActiveFormattingScopeMarker &&
           !is_open_element(parser, element));

  ++i;
  gumbo_debug("Reconstructing elements from %d on %s parent.\n", i,
      gumbo_normalized_tagname(get_current_node(parser)->v.element.tag));
  for (; i < elements->length; ++i) {
    assert(elements->length > 0);
    assert(i < elements->length);
    element = elements->data[i];
    assert(element != &kActiveFormattingScopeMarker);
    GumboNode* clone = clone_node(
        parser, element, GUMBO_INSERTION_RECONSTRUCTED_FORMATTING_ELEMENT);
    InsertionLocation location =
        get_appropriate_insertion_location(parser, NULL);
    insert_node(parser, clone, location);
    gumbo_vector_add(
        parser, (void*) clone, &parser->_parser_state->_open_elements);

    elements->data[i] = clone;
    gumbo_debug("Reconstructed %s element at %d.\n",
        gumbo_normalized_tagname(clone->v.element.tag), i);
  }
}

static void clear_active_formatting_elements(GumboParser* parser) {
  GumboVector* elements = &parser->_parser_state->_active_formatting_elements;
  int num_elements_cleared = 0;
  const GumboNode* node;
  do {
    node = gumbo_vector_pop(parser, elements);
    ++num_elements_cleared;
  } while (node && node != &kActiveFormattingScopeMarker);
  gumbo_debug("Cleared %d elements from active formatting list.\n",
      num_elements_cleared);
}

static GumboQuirksModeEnum compute_quirks_mode(
    const GumboTokenDocType* doctype) {
  if (doctype->force_quirks || strcmp(doctype->name, kDoctypeHtml.data) ||
      is_in_static_list(
          doctype->public_identifier, kQuirksModePublicIdPrefixes, false) ||
      is_in_static_list(
          doctype->public_identifier, kQuirksModePublicIdExactMatches, true) ||
      is_in_static_list(
          doctype->system_identifier, kQuirksModeSystemIdExactMatches, true) ||
      (is_in_static_list(doctype->public_identifier,
           kLimitedQuirksRequiresSystemIdPublicIdPrefixes, false) &&
          !doctype->has_system_identifier)) {
    return GUMBO_DOCTYPE_QUIRKS;
  } else if (is_in_static_list(doctype->public_identifier,
                 kLimitedQuirksPublicIdPrefixes, false) ||
             (is_in_static_list(doctype->public_identifier,
                  kLimitedQuirksRequiresSystemIdPublicIdPrefixes, false) &&
                 doctype->has_system_identifier)) {
    return GUMBO_DOCTYPE_LIMITED_QUIRKS;
  }
  return GUMBO_DOCTYPE_NO_QUIRKS;
}

static bool has_an_element_in_specific_scope(GumboParser* parser,
    int expected_size, const GumboTag* expected, bool negate,
    const gumbo_tagset tags) {
  GumboVector* open_elements = &parser->_parser_state->_open_elements;
  for (int i = open_elements->length; --i >= 0;) {
    const GumboNode* node = open_elements->data[i];
    if (node->type != GUMBO_NODE_ELEMENT && node->type != GUMBO_NODE_TEMPLATE)
      continue;

    GumboTag node_tag = node->v.element.tag;
    GumboNamespaceEnum node_ns = node->v.element.tag_namespace;
    for (int j = 0; j < expected_size; ++j) {
      if (node_tag == expected[j] && node_ns == GUMBO_NAMESPACE_HTML)
        return true;
    }

    bool found = TAGSET_INCLUDES(tags, node_ns, node_tag);
    if (negate != found) return false;
  }
  return false;
}

static bool has_open_element(GumboParser* parser, GumboTag tag) {
  return has_an_element_in_specific_scope(
      parser, 1, &tag, false, (gumbo_tagset){TAG(HTML)});
}

static bool has_an_element_in_scope(GumboParser* parser, GumboTag tag) {
  return has_an_element_in_specific_scope(parser, 1, &tag, false,
      (gumbo_tagset){TAG(APPLET), TAG(CAPTION), TAG(HTML), TAG(TABLE), TAG(TD),
          TAG(TH), TAG(MARQUEE), TAG(OBJECT), TAG(TEMPLATE), TAG_MATHML(MI),
          TAG_MATHML(MO), TAG_MATHML(MN), TAG_MATHML(MS), TAG_MATHML(MTEXT),
          TAG_MATHML(ANNOTATION_XML), TAG_SVG(FOREIGNOBJECT), TAG_SVG(DESC),
          TAG_SVG(TITLE)});
}

static bool has_node_in_scope(GumboParser* parser, const GumboNode* node) {
  GumboVector* open_elements = &parser->_parser_state->_open_elements;
  for (int i = open_elements->length; --i >= 0;) {
    const GumboNode* current = open_elements->data[i];
    if (current == node) {
      return true;
    }
    if (current->type != GUMBO_NODE_ELEMENT &&
        current->type != GUMBO_NODE_TEMPLATE) {
      continue;
    }
    if (node_tag_in_set(current,
            (gumbo_tagset){TAG(APPLET), TAG(CAPTION), TAG(HTML), TAG(TABLE),
                TAG(TD), TAG(TH), TAG(MARQUEE), TAG(OBJECT), TAG(TEMPLATE),
                TAG_MATHML(MI), TAG_MATHML(MO), TAG_MATHML(MN), TAG_MATHML(MS),
                TAG_MATHML(MTEXT), TAG_MATHML(ANNOTATION_XML),
                TAG_SVG(FOREIGNOBJECT), TAG_SVG(DESC), TAG_SVG(TITLE)})) {
      return false;
    }
  }
  assert(false);
  return false;
}

static bool has_an_element_in_scope_with_tagname(
    GumboParser* parser, int expected_len, const GumboTag expected[]) {
  return has_an_element_in_specific_scope(parser, expected_len, expected, false,
      (gumbo_tagset){TAG(APPLET), TAG(CAPTION), TAG(HTML), TAG(TABLE), TAG(TD),
          TAG(TH), TAG(MARQUEE), TAG(OBJECT), TAG(TEMPLATE), TAG_MATHML(MI),
          TAG_MATHML(MO), TAG_MATHML(MN), TAG_MATHML(MS), TAG_MATHML(MTEXT),
          TAG_MATHML(ANNOTATION_XML), TAG_SVG(FOREIGNOBJECT), TAG_SVG(DESC),
          TAG_SVG(TITLE)});
}

static bool has_an_element_in_list_scope(GumboParser* parser, GumboTag tag) {
  return has_an_element_in_specific_scope(parser, 1, &tag, false,
      (gumbo_tagset){TAG(APPLET), TAG(CAPTION), TAG(HTML), TAG(TABLE), TAG(TD),
          TAG(TH), TAG(MARQUEE), TAG(OBJECT), TAG(TEMPLATE), TAG_MATHML(MI),
          TAG_MATHML(MO), TAG_MATHML(MN), TAG_MATHML(MS), TAG_MATHML(MTEXT),
          TAG_MATHML(ANNOTATION_XML), TAG_SVG(FOREIGNOBJECT), TAG_SVG(DESC),
          TAG_SVG(TITLE), TAG(OL), TAG(UL)});
}

static bool has_an_element_in_button_scope(GumboParser* parser, GumboTag tag) {
  return has_an_element_in_specific_scope(parser, 1, &tag, false,
      (gumbo_tagset){TAG(APPLET), TAG(CAPTION), TAG(HTML), TAG(TABLE), TAG(TD),
          TAG(TH), TAG(MARQUEE), TAG(OBJECT), TAG(TEMPLATE), TAG_MATHML(MI),
          TAG_MATHML(MO), TAG_MATHML(MN), TAG_MATHML(MS), TAG_MATHML(MTEXT),
          TAG_MATHML(ANNOTATION_XML), TAG_SVG(FOREIGNOBJECT), TAG_SVG(DESC),
          TAG_SVG(TITLE), TAG(BUTTON)});
}

static bool has_an_element_in_table_scope(GumboParser* parser, GumboTag tag) {
  return has_an_element_in_specific_scope(parser, 1, &tag, false,
      (gumbo_tagset){TAG(HTML), TAG(TABLE), TAG(TEMPLATE)});
}

static bool has_an_element_in_select_scope(GumboParser* parser, GumboTag tag) {
  return has_an_element_in_specific_scope(
      parser, 1, &tag, true, (gumbo_tagset){TAG(OPTGROUP), TAG(OPTION)});
}

static void generate_implied_end_tags(GumboParser* parser, GumboTag exception) {
  for (; node_tag_in_set(get_current_node(parser),
             (gumbo_tagset){TAG(DD), TAG(DT), TAG(LI), TAG(OPTION),
                 TAG(OPTGROUP), TAG(P), TAG(RP), TAG(RB), TAG(RT), TAG(RTC)}) &&
         !node_html_tag_is(get_current_node(parser), exception);
       pop_current_node(parser))
    ;
}

static void generate_all_implied_end_tags_thoroughly(GumboParser* parser) {
  for (
      ; node_tag_in_set(get_current_node(parser),
          (gumbo_tagset){TAG(CAPTION), TAG(COLGROUP), TAG(DD), TAG(DT), TAG(LI),
              TAG(OPTION), TAG(OPTGROUP), TAG(P), TAG(RP), TAG(RT), TAG(RTC),
              TAG(TBODY), TAG(TD), TAG(TFOOT), TAG(TH), TAG(HEAD), TAG(TR)});
      pop_current_node(parser))
    ;
}

static bool close_table(GumboParser* parser) {
  if (!has_an_element_in_table_scope(parser, GUMBO_TAG_TABLE)) {
    return false;
  }

  GumboNode* node = pop_current_node(parser);
  while (!node_html_tag_is(node, GUMBO_TAG_TABLE)) {
    node = pop_current_node(parser);
  }
  reset_insertion_mode_appropriately(parser);
  return true;
}

static bool close_table_cell(
    GumboParser* parser, const GumboToken* token, GumboTag cell_tag) {
  bool result = true;
  generate_implied_end_tags(parser, GUMBO_TAG_LAST);
  const GumboNode* node = get_current_node(parser);
  if (!node_html_tag_is(node, cell_tag)) {
    parser_add_parse_error(parser, token);
    result = false;
  }
  do {
    node = pop_current_node(parser);
  } while (!node_html_tag_is(node, cell_tag));

  clear_active_formatting_elements(parser);
  set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_ROW);
  return result;
}

static bool close_current_cell(GumboParser* parser, const GumboToken* token) {
  if (has_an_element_in_table_scope(parser, GUMBO_TAG_TD)) {
    assert(!has_an_element_in_table_scope(parser, GUMBO_TAG_TH));
    return close_table_cell(parser, token, GUMBO_TAG_TD);
  } else {
    assert(has_an_element_in_table_scope(parser, GUMBO_TAG_TH));
    return close_table_cell(parser, token, GUMBO_TAG_TH);
  }
}

static void close_current_select(GumboParser* parser) {
  GumboNode* node = pop_current_node(parser);
  while (!node_html_tag_is(node, GUMBO_TAG_SELECT)) {
    node = pop_current_node(parser);
  }
  reset_insertion_mode_appropriately(parser);
}

static bool is_special_node(const GumboNode* node) {
  assert(node->type == GUMBO_NODE_ELEMENT || node->type == GUMBO_NODE_TEMPLATE);
  return node_tag_in_set(node,
      (gumbo_tagset){TAG(ADDRESS), TAG(APPLET), TAG(AREA), TAG(ARTICLE),
          TAG(ASIDE), TAG(BASE), TAG(BASEFONT), TAG(BGSOUND), TAG(BLOCKQUOTE),
          TAG(BODY), TAG(BR), TAG(BUTTON), TAG(CAPTION), TAG(CENTER), TAG(COL),
          TAG(COLGROUP), TAG(DD), TAG(DETAILS), TAG(DIR),
          TAG(DIV), TAG(DL), TAG(DT), TAG(EMBED), TAG(FIELDSET),
          TAG(FIGCAPTION), TAG(FIGURE), TAG(FOOTER), TAG(FORM), TAG(FRAME),
          TAG(FRAMESET), TAG(H1), TAG(H2), TAG(H3), TAG(H4), TAG(H5), TAG(H6),
          TAG(HEAD), TAG(HEADER), TAG(HGROUP), TAG(HR), TAG(HTML), TAG(IFRAME),
          TAG(IMG), TAG(INPUT), TAG(LI), TAG(LINK), TAG(LISTING),
          TAG(MARQUEE), TAG(MENU), TAG(META), TAG(NAV), TAG(NOEMBED),
          TAG(NOFRAMES), TAG(NOSCRIPT), TAG(OBJECT), TAG(OL), TAG(P),
          TAG(PARAM), TAG(PLAINTEXT), TAG(PRE), TAG(SCRIPT), TAG(SECTION),
          TAG(SELECT), TAG(STYLE), TAG(SUMMARY), TAG(TABLE), TAG(TBODY),
          TAG(TD), TAG(TEMPLATE), TAG(TEXTAREA), TAG(TFOOT), TAG(TH),
          TAG(THEAD), TAG(TITLE), TAG(TR), TAG(UL), TAG(WBR), TAG(XMP),

          TAG_MATHML(MI), TAG_MATHML(MO), TAG_MATHML(MN), TAG_MATHML(MS),
          TAG_MATHML(MTEXT), TAG_MATHML(ANNOTATION_XML),

          TAG_SVG(FOREIGNOBJECT), TAG_SVG(DESC)});
}

static bool implicitly_close_tags(GumboParser* parser, GumboToken* token,
    GumboNamespaceEnum target_ns, GumboTag target) {
  bool result = true;
  generate_implied_end_tags(parser, target);
  if (!node_qualified_tag_is(get_current_node(parser), target_ns, target)) {
    parser_add_parse_error(parser, token);
    while (
        !node_qualified_tag_is(get_current_node(parser), target_ns, target)) {
      pop_current_node(parser);
    }
    result = false;
  }
  assert(node_qualified_tag_is(get_current_node(parser), target_ns, target));
  pop_current_node(parser);
  return result;
}

static bool maybe_implicitly_close_p_tag(
    GumboParser* parser, GumboToken* token) {
  if (has_an_element_in_button_scope(parser, GUMBO_TAG_P)) {
    return implicitly_close_tags(
        parser, token, GUMBO_NAMESPACE_HTML, GUMBO_TAG_P);
  }
  return true;
}

static void maybe_implicitly_close_list_tag(
    GumboParser* parser, GumboToken* token, bool is_li) {
  GumboParserState* state = parser->_parser_state;
  state->_frameset_ok = false;
  for (int i = state->_open_elements.length; --i >= 0;) {
    const GumboNode* node = state->_open_elements.data[i];
    bool is_list_tag =
        is_li ? node_html_tag_is(node, GUMBO_TAG_LI)
              : node_tag_in_set(node, (gumbo_tagset){TAG(DD), TAG(DT)});
    if (is_list_tag) {
      implicitly_close_tags(
          parser, token, node->v.element.tag_namespace, node->v.element.tag);
      return;
    }
    if (is_special_node(node) &&
        !node_tag_in_set(
            node, (gumbo_tagset){TAG(ADDRESS), TAG(DIV), TAG(P)})) {
      return;
    }
  }
}

static void merge_attributes(
    GumboParser* parser, GumboToken* token, GumboNode* node) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  assert(node->type == GUMBO_NODE_ELEMENT);
  const GumboVector* token_attr = &token->v.start_tag.attributes;
  GumboVector* node_attr = &node->v.element.attributes;

  for (unsigned int i = 0; i < token_attr->length; ++i) {
    GumboAttribute* attr = token_attr->data[i];
    if (!gumbo_get_attribute(node_attr, attr->name)) {
      gumbo_vector_add(parser, attr, node_attr);
      token_attr->data[i] = NULL;
    }
  }
  gumbo_token_destroy(parser, token);

#ifndef NDEBUG
  token->v.start_tag.attributes = kGumboEmptyVector;
#endif
}

const char* gumbo_normalize_svg_tagname(const GumboStringPiece* tag) {
  for (size_t i = 0; i < sizeof(kSvgTagReplacements) / sizeof(ReplacementEntry);
       ++i) {
    const ReplacementEntry* entry = &kSvgTagReplacements[i];
    if (gumbo_string_equals_ignore_case(tag, &entry->from)) {
      return entry->to.data;
    }
  }
  return NULL;
}

static void adjust_foreign_attributes(GumboParser* parser, GumboToken* token) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  const GumboVector* attributes = &token->v.start_tag.attributes;
  for (size_t i = 0; i < sizeof(kForeignAttributeReplacements) /
                             sizeof(NamespacedAttributeReplacement);
       ++i) {
    const NamespacedAttributeReplacement* entry =
        &kForeignAttributeReplacements[i];
    GumboAttribute* attr = gumbo_get_attribute(attributes, entry->from);
    if (!attr) {
      continue;
    }
    gumbo_parser_deallocate(parser, (void*) attr->name);
    attr->attr_namespace = entry->attr_namespace;
    attr->name = gumbo_copy_stringz(parser, entry->local_name);
  }
}

static void adjust_svg_attributes(GumboParser* parser, GumboToken* token) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  const GumboVector* attributes = &token->v.start_tag.attributes;
  for (size_t i = 0;
       i < sizeof(kSvgAttributeReplacements) / sizeof(ReplacementEntry); ++i) {
    const ReplacementEntry* entry = &kSvgAttributeReplacements[i];
    GumboAttribute* attr = gumbo_get_attribute(attributes, entry->from.data);
    if (!attr) {
      continue;
    }
    gumbo_parser_deallocate(parser, (void*) attr->name);
    attr->name = gumbo_copy_stringz(parser, entry->to.data);
  }
}

static void adjust_mathml_attributes(GumboParser* parser, GumboToken* token) {
  assert(token->type == GUMBO_TOKEN_START_TAG);
  GumboAttribute* attr =
      gumbo_get_attribute(&token->v.start_tag.attributes, "definitionurl");
  if (!attr) {
    return;
  }
  gumbo_parser_deallocate(parser, (void*) attr->name);
  attr->name = gumbo_copy_stringz(parser, "definitionURL");
}

static bool doctype_matches(const GumboTokenDocType* doctype,
    const GumboStringPiece* public_id, const GumboStringPiece* system_id,
    bool allow_missing_system_id) {
  return !strcmp(doctype->public_identifier, public_id->data) &&
         (allow_missing_system_id || doctype->has_system_identifier) &&
         !strcmp(doctype->system_identifier, system_id->data);
}

static bool maybe_add_doctype_error(
    GumboParser* parser, const GumboToken* token) {
  const GumboTokenDocType* doctype = &token->v.doc_type;
  bool html_doctype = !strcmp(doctype->name, kDoctypeHtml.data);
  if ((!html_doctype || doctype->has_public_identifier ||
          (doctype->has_system_identifier &&
              !strcmp(
                  doctype->system_identifier, kSystemIdLegacyCompat.data))) &&
      !(html_doctype && (doctype_matches(doctype, &kPublicIdHtml4_0,
                             &kSystemIdRecHtml4_0, true) ||
                            doctype_matches(doctype, &kPublicIdHtml4_01,
                                &kSystemIdHtml4, true) ||
                            doctype_matches(doctype, &kPublicIdXhtml1_0,
                                &kSystemIdXhtmlStrict1_1, false) ||
                            doctype_matches(doctype, &kPublicIdXhtml1_1,
                                &kSystemIdXhtml1_1, false)))) {
    parser_add_parse_error(parser, token);
    return false;
  }
  return true;
}

static void remove_from_parent(GumboParser* parser, GumboNode* node) {
  if (!node->parent) {
    return;
  }
  assert(node->parent->type == GUMBO_NODE_ELEMENT);
  GumboVector* children = &node->parent->v.element.children;
  int index = gumbo_vector_index_of(children, node);
  assert(index != -1);

  gumbo_vector_remove_at(parser, index, children);
  node->parent = NULL;
  node->index_within_parent = -1;
  for (unsigned int i = index; i < children->length; ++i) {
    GumboNode* child = children->data[i];
    child->index_within_parent = i;
  }
}

static bool adoption_agency_algorithm(
    GumboParser* parser, GumboToken* token, GumboTag subject) {
  GumboParserState* state = parser->_parser_state;
  gumbo_debug("Entering adoption agency algorithm.\n");
  GumboNode* current_node = get_current_node(parser);
  if (current_node->v.element.tag_namespace == GUMBO_NAMESPACE_HTML &&
      current_node->v.element.tag == subject &&
      gumbo_vector_index_of(
          &state->_active_formatting_elements, current_node) == -1) {
    pop_current_node(parser);
    return false;
  }
  for (unsigned int i = 0; i < 8; ++i) {
    GumboNode* formatting_node = NULL;
    int formatting_node_in_open_elements = -1;
    for (int j = state->_active_formatting_elements.length; --j >= 0;) {
      GumboNode* current_node = state->_active_formatting_elements.data[j];
      if (current_node == &kActiveFormattingScopeMarker) {
        gumbo_debug("Broke on scope marker; aborting.\n");
        return false;
      }
      if (node_html_tag_is(current_node, subject)) {
        formatting_node = current_node;
        formatting_node_in_open_elements =
            gumbo_vector_index_of(&state->_open_elements, formatting_node);
        gumbo_debug("Formatting element of tag %s at %d.\n",
            gumbo_normalized_tagname(subject),
            formatting_node_in_open_elements);
        break;
      }
    }
    if (!formatting_node) {
      gumbo_debug("No active formatting elements; aborting.\n");
      return false;
    }

    if (formatting_node_in_open_elements == -1) {
      gumbo_debug("Formatting node not on stack of open elements.\n");
      parser_add_parse_error(parser, token);
      gumbo_vector_remove(
          parser, formatting_node, &state->_active_formatting_elements);
      return false;
    }

    if (!has_an_element_in_scope(parser, formatting_node->v.element.tag)) {
      parser_add_parse_error(parser, token);
      gumbo_debug("Element not in scope.\n");
      return false;
    }

    if (formatting_node != get_current_node(parser)) {
      parser_add_parse_error(parser, token);
    }
    assert(formatting_node);
    assert(!node_html_tag_is(formatting_node, GUMBO_TAG_HTML));
    assert(!node_html_tag_is(formatting_node, GUMBO_TAG_BODY));

    GumboNode* furthest_block = NULL;
    for (unsigned int j = formatting_node_in_open_elements;
         j < state->_open_elements.length; ++j) {
      assert(j > 0);
      GumboNode* current = state->_open_elements.data[j];
      if (is_special_node(current)) {
        furthest_block = current;
        break;
      }
    }
    if (!furthest_block) {
      while (get_current_node(parser) != formatting_node) {
        pop_current_node(parser);
      }
      pop_current_node(parser);
      gumbo_vector_remove(
          parser, formatting_node, &state->_active_formatting_elements);
      return false;
    }
    assert(!node_html_tag_is(furthest_block, GUMBO_TAG_HTML));
    assert(furthest_block);

    GumboNode* common_ancestor =
        state->_open_elements.data[gumbo_vector_index_of(&state->_open_elements,
                                       formatting_node) -
                                   1];
    gumbo_debug("Common ancestor tag = %s, furthest block tag = %s.\n",
        gumbo_normalized_tagname(common_ancestor->v.element.tag),
        gumbo_normalized_tagname(furthest_block->v.element.tag));

    int bookmark = gumbo_vector_index_of(
                       &state->_active_formatting_elements, formatting_node) +
                   1;
    gumbo_debug("Bookmark at %d.\n", bookmark);
    GumboNode* node = furthest_block;
    GumboNode* last_node = furthest_block;
    int saved_node_index = gumbo_vector_index_of(&state->_open_elements, node);
    assert(saved_node_index > 0);
    for (int j = 0;;) {
      ++j;
      int node_index = gumbo_vector_index_of(&state->_open_elements, node);
      gumbo_debug(
          "Current index: %d, last index: %d.\n", node_index, saved_node_index);
      if (node_index == -1) {
        node_index = saved_node_index;
      }
      saved_node_index = --node_index;
      assert(node_index > 0);
      assert((unsigned int) node_index < state->_open_elements.capacity);
      node = state->_open_elements.data[node_index];
      assert(node->parent);
      if (node == formatting_node) {
        break;
      }
      int formatting_index =
          gumbo_vector_index_of(&state->_active_formatting_elements, node);
      if (j > 3 && formatting_index != -1) {
        gumbo_debug("Removing formatting element at %d.\n", formatting_index);
        gumbo_vector_remove_at(
            parser, formatting_index, &state->_active_formatting_elements);
        if (formatting_index < bookmark) {
          --bookmark;
          gumbo_debug("Moving bookmark to %d.\n", bookmark);
        }
        continue;
      }
      if (formatting_index == -1) {
        gumbo_vector_remove_at(parser, node_index, &state->_open_elements);
        continue;
      }
      node = clone_node(parser, node, GUMBO_INSERTION_ADOPTION_AGENCY_CLONED);
      assert(formatting_index >= 0);
      state->_active_formatting_elements.data[formatting_index] = node;
      assert(node_index >= 0);
      state->_open_elements.data[node_index] = node;
      if (last_node == furthest_block) {
        bookmark = formatting_index + 1;
        gumbo_debug("Bookmark moved to %d.\n", bookmark);
        assert((unsigned int) bookmark <= state->_active_formatting_elements.length);
      }
      last_node->parse_flags |= GUMBO_INSERTION_ADOPTION_AGENCY_MOVED;
      remove_from_parent(parser, last_node);
      append_node(parser, node, last_node);
      last_node = node;
    }

    gumbo_debug("Removing %s node from parent ",
        gumbo_normalized_tagname(last_node->v.element.tag));
    remove_from_parent(parser, last_node);
    last_node->parse_flags |= GUMBO_INSERTION_ADOPTION_AGENCY_MOVED;
    InsertionLocation location =
        get_appropriate_insertion_location(parser, common_ancestor);
    gumbo_debug("and inserting it into %s.\n",
        gumbo_normalized_tagname(location.target->v.element.tag));
    insert_node(parser, last_node, location);

    GumboNode* new_formatting_node = clone_node(
        parser, formatting_node, GUMBO_INSERTION_ADOPTION_AGENCY_CLONED);
    formatting_node->parse_flags |= GUMBO_INSERTION_IMPLICIT_END_TAG;

    GumboVector temp = new_formatting_node->v.element.children;
    new_formatting_node->v.element.children =
        furthest_block->v.element.children;
    furthest_block->v.element.children = temp;

    temp = new_formatting_node->v.element.children;
    for (unsigned int i = 0; i < temp.length; ++i) {
      GumboNode* child = temp.data[i];
      child->parent = new_formatting_node;
    }

    append_node(parser, furthest_block, new_formatting_node);

    int formatting_node_index = gumbo_vector_index_of(
        &state->_active_formatting_elements, formatting_node);
    assert(formatting_node_index != -1);
    if (formatting_node_index < bookmark) {
      gumbo_debug(
          "Formatting node at %d is before bookmark at %d; decrementing.\n",
          formatting_node_index, bookmark);
      --bookmark;
    }
    gumbo_vector_remove_at(
        parser, formatting_node_index, &state->_active_formatting_elements);
    assert(bookmark >= 0);
    assert((unsigned int) bookmark <= state->_active_formatting_elements.length);
    gumbo_vector_insert_at(parser, new_formatting_node, bookmark,
        &state->_active_formatting_elements);

    gumbo_vector_remove(parser, formatting_node, &state->_open_elements);
    int insert_at =
        gumbo_vector_index_of(&state->_open_elements, furthest_block) + 1;
    assert(insert_at >= 0);
    assert((unsigned int) insert_at <= state->_open_elements.length);
    gumbo_vector_insert_at(
        parser, new_formatting_node, insert_at, &state->_open_elements);
  }
  return true;
}

static void ignore_token(GumboParser* parser) {
  GumboToken* token = parser->_parser_state->_current_token;
  gumbo_token_destroy(parser, token);
#ifndef NDEBUG
  if (token->type == GUMBO_TOKEN_START_TAG) {
    token->v.start_tag.attributes = kGumboEmptyVector;
  }
#endif
}

static void finish_parsing(GumboParser* parser) {
  gumbo_debug("Finishing parsing");
  maybe_flush_text_node_buffer(parser);
  GumboParserState* state = parser->_parser_state;
  for (GumboNode* node = pop_current_node(parser); node;
       node = pop_current_node(parser)) {
    if ((node_html_tag_is(node, GUMBO_TAG_BODY) && state->_closed_body_tag) ||
        (node_html_tag_is(node, GUMBO_TAG_HTML) && state->_closed_html_tag)) {
      continue;
    }
    node->parse_flags |= GUMBO_INSERTION_IMPLICIT_END_TAG;
  }
  while (pop_current_node(parser))
    ;
}

static bool handle_initial(GumboParser* parser, GumboToken* token) {
  GumboDocument* document = &get_document_node(parser)->v.document;
  if (token->type == GUMBO_TOKEN_WHITESPACE) {
    ignore_token(parser);
    return true;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_document_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    document->has_doctype = true;
    document->name = token->v.doc_type.name;
    document->public_identifier = token->v.doc_type.public_identifier;
    document->system_identifier = token->v.doc_type.system_identifier;
    document->doc_type_quirks_mode = compute_quirks_mode(&token->v.doc_type);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_BEFORE_HTML);
    return maybe_add_doctype_error(parser, token);
  }
  parser_add_parse_error(parser, token);
  document->doc_type_quirks_mode = GUMBO_DOCTYPE_QUIRKS;
  set_insertion_mode(parser, GUMBO_INSERTION_MODE_BEFORE_HTML);
  parser->_parser_state->_reprocess_current_token = true;
  return true;
}

static bool handle_before_html(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_document_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_WHITESPACE) {
    ignore_token(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    GumboNode* html_node = insert_element_from_token(parser, token);
    parser->_output->root = html_node;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_BEFORE_HEAD);
    return true;
  } else if (token->type == GUMBO_TOKEN_END_TAG &&
             !tag_in(token, false,
                 (gumbo_tagset){TAG(HEAD), TAG(BODY), TAG(HTML), TAG(BR)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    GumboNode* html_node = insert_element_of_tag_type(
        parser, GUMBO_TAG_HTML, GUMBO_INSERTION_IMPLIED);
    assert(html_node);
    parser->_output->root = html_node;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_BEFORE_HEAD);
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  }
}

static bool handle_before_head(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_WHITESPACE) {
    ignore_token(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HEAD)) {
    GumboNode* node = insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_HEAD);
    parser->_parser_state->_head_element = node;
    return true;
  } else if (token->type == GUMBO_TOKEN_END_TAG &&
             !tag_in(token, false,
                 (gumbo_tagset){TAG(HEAD), TAG(BODY), TAG(HTML), TAG(BR)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    GumboNode* node = insert_element_of_tag_type(
        parser, GUMBO_TAG_HEAD, GUMBO_INSERTION_IMPLIED);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_HEAD);
    parser->_parser_state->_head_element = node;
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  }
}

static bool handle_token(GumboParser* parser, GumboToken* token);
static bool handle_in_body(GumboParser* parser, GumboToken* token);

static bool handle_in_head(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(BASE), TAG(BASEFONT), TAG(BGSOUND),
                     TAG(LINK)})) {
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_META)) {
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TITLE)) {
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_RCDATA);
    return true;
  } else if (tag_in(
                 token, kStartTag, (gumbo_tagset){TAG(NOFRAMES), TAG(STYLE)})) {
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_RAWTEXT);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_NOSCRIPT)) {
    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_HEAD_NOSCRIPT);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_SCRIPT)) {
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_SCRIPT);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_HEAD)) {
    GumboNode* head = pop_current_node(parser);
    AVOID_UNUSED_VARIABLE_WARNING(head);
    assert(node_html_tag_is(head, GUMBO_TAG_HEAD));
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_HEAD);
    return true;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(BODY), TAG(HTML), TAG(BR)})) {
    pop_current_node(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_HEAD);
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TEMPLATE)) {
    insert_element_from_token(parser, token);
    add_formatting_element(parser, &kActiveFormattingScopeMarker);
    parser->_parser_state->_frameset_ok = false;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TEMPLATE);
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TEMPLATE);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE)) {
    if (!has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    generate_all_implied_end_tags_thoroughly(parser);
    bool success = true;
    if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_TEMPLATE)) {
      parser_add_parse_error(parser, token);
      success = false;
    }
    while (!node_html_tag_is(pop_current_node(parser), GUMBO_TAG_TEMPLATE))
      ;
    clear_active_formatting_elements(parser);
    pop_template_insertion_mode(parser);
    reset_insertion_mode_appropriately(parser);
    return success;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HEAD) ||
             (token->type == GUMBO_TOKEN_END_TAG)) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    pop_current_node(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_HEAD);
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  }
  return true;
}

static bool handle_in_head_noscript(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_is(token, kEndTag, GUMBO_TAG_NOSCRIPT)) {
    const GumboNode* node = pop_current_node(parser);
    assert(node_html_tag_is(node, GUMBO_TAG_NOSCRIPT));
    AVOID_UNUSED_VARIABLE_WARNING(node);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_HEAD);
    return true;
  } else if (token->type == GUMBO_TOKEN_WHITESPACE ||
             token->type == GUMBO_TOKEN_COMMENT ||
             tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(BASEFONT), TAG(BGSOUND), TAG(LINK),
                     TAG(META), TAG(NOFRAMES), TAG(STYLE)})) {
    return handle_in_head(parser, token);
  } else if (tag_in(
                 token, kStartTag, (gumbo_tagset){TAG(HEAD), TAG(NOSCRIPT)}) ||
             (token->type == GUMBO_TOKEN_END_TAG &&
                 !tag_is(token, kEndTag, GUMBO_TAG_BR))) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    parser_add_parse_error(parser, token);
    const GumboNode* node = pop_current_node(parser);
    assert(node_html_tag_is(node, GUMBO_TAG_NOSCRIPT));
    AVOID_UNUSED_VARIABLE_WARNING(node);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_HEAD);
    parser->_parser_state->_reprocess_current_token = true;
    return false;
  }
}

static bool handle_after_head(GumboParser* parser, GumboToken* token) {
  GumboParserState* state = parser->_parser_state;
  if (token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_BODY)) {
    insert_element_from_token(parser, token);
    state->_frameset_ok = false;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_BODY);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_FRAMESET)) {
    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_FRAMESET);
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(BASE), TAG(BASEFONT), TAG(BGSOUND),
                     TAG(LINK), TAG(META), TAG(NOFRAMES), TAG(SCRIPT),
                     TAG(STYLE), TAG(TEMPLATE), TAG(TITLE)})) {
    parser_add_parse_error(parser, token);
    assert(state->_head_element != NULL);
    maybe_flush_text_node_buffer(parser);
    gumbo_vector_add(parser, state->_head_element, &state->_open_elements);
    bool result = handle_in_head(parser, token);
    gumbo_vector_remove(parser, state->_head_element, &state->_open_elements);
    return result;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE)) {
    return handle_in_head(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HEAD) ||
             (token->type == GUMBO_TOKEN_END_TAG &&
                 !tag_in(token, kEndTag,
                     (gumbo_tagset){TAG(BODY), TAG(HTML), TAG(BR)}))) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    insert_element_of_tag_type(parser, GUMBO_TAG_BODY, GUMBO_INSERTION_IMPLIED);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_BODY);
    state->_reprocess_current_token = true;
    return true;
  }
}

static void destroy_node(GumboParser* parser, GumboNode* node) {
  switch (node->type) {
    case GUMBO_NODE_DOCUMENT: {
      GumboDocument* doc = &node->v.document;
      for (unsigned int i = 0; i < doc->children.length; ++i) {
        destroy_node(parser, doc->children.data[i]);
      }
      gumbo_parser_deallocate(parser, (void*) doc->children.data);
      gumbo_parser_deallocate(parser, (void*) doc->name);
      gumbo_parser_deallocate(parser, (void*) doc->public_identifier);
      gumbo_parser_deallocate(parser, (void*) doc->system_identifier);
    } break;
    case GUMBO_NODE_TEMPLATE:
    case GUMBO_NODE_ELEMENT:
      for (unsigned int i = 0; i < node->v.element.attributes.length; ++i) {
        gumbo_destroy_attribute(parser, node->v.element.attributes.data[i]);
      }
      gumbo_parser_deallocate(parser, node->v.element.attributes.data);
      for (unsigned int i = 0; i < node->v.element.children.length; ++i) {
        destroy_node(parser, node->v.element.children.data[i]);
      }
      gumbo_parser_deallocate(parser, node->v.element.children.data);
      break;
    case GUMBO_NODE_TEXT:
    case GUMBO_NODE_CDATA:
    case GUMBO_NODE_COMMENT:
    case GUMBO_NODE_WHITESPACE:
      gumbo_parser_deallocate(parser, (void*) node->v.text.text);
      break;
  }
  gumbo_parser_deallocate(parser, node);
}

static bool handle_in_body(GumboParser* parser, GumboToken* token) {
  GumboParserState* state = parser->_parser_state;
  assert(state->_open_elements.length > 0);
  if (token->type == GUMBO_TOKEN_NULL) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_WHITESPACE) {
    reconstruct_active_formatting_elements(parser);
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_CHARACTER ||
             token->type == GUMBO_TOKEN_CDATA) {
    reconstruct_active_formatting_elements(parser);
    insert_text_token(parser, token);
    set_frameset_not_ok(parser);
    return true;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    parser_add_parse_error(parser, token);
    if (has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      ignore_token(parser);
      return false;
    }
    assert(parser->_output->root != NULL);
    assert(parser->_output->root->type == GUMBO_NODE_ELEMENT);
    merge_attributes(parser, token, parser->_output->root);
    return false;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(BASE), TAG(BASEFONT), TAG(BGSOUND),
                     TAG(LINK), TAG(META), TAG(NOFRAMES),
                     TAG(SCRIPT), TAG(STYLE), TAG(TEMPLATE), TAG(TITLE)}) ||
             tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE)) {
    return handle_in_head(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_BODY)) {
    parser_add_parse_error(parser, token);
    if (state->_open_elements.length < 2 ||
        !node_html_tag_is(state->_open_elements.data[1], GUMBO_TAG_BODY) ||
        has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      ignore_token(parser);
      return false;
    }
    state->_frameset_ok = false;
    merge_attributes(parser, token, state->_open_elements.data[1]);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_FRAMESET)) {
    parser_add_parse_error(parser, token);
    if (state->_open_elements.length < 2 ||
        !node_html_tag_is(state->_open_elements.data[1], GUMBO_TAG_BODY) ||
        !state->_frameset_ok) {
      ignore_token(parser);
      return false;
    }
    GumboNode* body_node = state->_open_elements.data[1];

    GumboNode* node;
    do {
      node = pop_current_node(parser);
    } while (node != state->_open_elements.data[1]);

    clear_active_formatting_elements(parser);

    GumboVector* children = &parser->_output->root->v.element.children;
    for (unsigned int i = 0; i < children->length; ++i) {
      if (children->data[i] == body_node) {
        gumbo_vector_remove_at(parser, i, children);
        break;
      }
    }
    destroy_node(parser, body_node);

    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_FRAMESET);
    return true;
  } else if (token->type == GUMBO_TOKEN_EOF) {
    for (unsigned int i = 0; i < state->_open_elements.length; ++i) {
      if (!node_tag_in_set(state->_open_elements.data[i],
              (gumbo_tagset){TAG(DD), TAG(DT), TAG(LI), TAG(P), TAG(TBODY),
                  TAG(TD), TAG(TFOOT), TAG(TH), TAG(THEAD), TAG(TR), TAG(BODY),
                  TAG(HTML)})) {
        parser_add_parse_error(parser, token);
      }
    }
    if (get_current_template_insertion_mode(parser) !=
        GUMBO_INSERTION_MODE_INITIAL) {
      return handle_in_template(parser, token);
    }
    return true;
  } else if (tag_in(token, kEndTag, (gumbo_tagset){TAG(BODY), TAG(HTML)})) {
    if (!has_an_element_in_scope(parser, GUMBO_TAG_BODY)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    bool success = true;
    for (unsigned int i = 0; i < state->_open_elements.length; ++i) {
      if (!node_tag_in_set(state->_open_elements.data[i],
              (gumbo_tagset){TAG(DD), TAG(DT), TAG(LI), TAG(OPTGROUP),
                  TAG(OPTION), TAG(P), TAG(RB), TAG(RP), TAG(RT), TAG(RTC),
                  TAG(TBODY), TAG(TD), TAG(TFOOT), TAG(TH), TAG(THEAD), TAG(TR),
                  TAG(BODY), TAG(HTML)})) {
        parser_add_parse_error(parser, token);
        success = false;
        break;
      }
    }
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_BODY);
    if (tag_is(token, kEndTag, GUMBO_TAG_HTML)) {
      parser->_parser_state->_reprocess_current_token = true;
    } else {
      GumboNode* body = state->_open_elements.data[1];
      assert(node_html_tag_is(body, GUMBO_TAG_BODY));
      record_end_of_element(state->_current_token, &body->v.element);
    }
    return success;
  } else if (tag_in(token, kStartTag, (gumbo_tagset){
        TAG(ADDRESS), TAG(ARTICLE), TAG(ASIDE), TAG(BLOCKQUOTE), TAG(CENTER),
        TAG(DETAILS), TAG(DIALOG), TAG(DIR), TAG(DIV), TAG(DL), TAG(FIELDSET),
        TAG(FIGCAPTION), TAG(FIGURE), TAG(FOOTER), TAG(HEADER), TAG(HGROUP),
        TAG(MENU), TAG(MAIN), TAG(NAV), TAG(OL), TAG(P), TAG(SECTION),
        TAG(SUMMARY), TAG(UL), TAG(SEARCH)}))
  {
    bool result = maybe_implicitly_close_p_tag(parser, token);
    insert_element_from_token(parser, token);
    return result;
  } else if (tag_in(token, kStartTag, (gumbo_tagset){TAG(H1), TAG(H2), TAG(H3),
                                          TAG(H4), TAG(H5), TAG(H6)})) {
    bool result = maybe_implicitly_close_p_tag(parser, token);
    if (node_tag_in_set(
            get_current_node(parser), (gumbo_tagset){TAG(H1), TAG(H2), TAG(H3),
                                          TAG(H4), TAG(H5), TAG(H6)})) {
      parser_add_parse_error(parser, token);
      pop_current_node(parser);
      result = false;
    }
    insert_element_from_token(parser, token);
    return result;
  } else if (tag_in(token, kStartTag, (gumbo_tagset){TAG(PRE), TAG(LISTING)})) {
    bool result = maybe_implicitly_close_p_tag(parser, token);
    insert_element_from_token(parser, token);
    state->_ignore_next_linefeed = true;
    state->_frameset_ok = false;
    return result;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_FORM)) {
    if (state->_form_element != NULL &&
        !has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      gumbo_debug("Ignoring nested form.\n");
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    bool result = maybe_implicitly_close_p_tag(parser, token);
    GumboNode* form_element = insert_element_from_token(parser, token);
    if (!has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      state->_form_element = form_element;
    }
    return result;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_LI)) {
    maybe_implicitly_close_list_tag(parser, token, true);
    bool result = maybe_implicitly_close_p_tag(parser, token);
    insert_element_from_token(parser, token);
    return result;
  } else if (tag_in(token, kStartTag, (gumbo_tagset){TAG(DD), TAG(DT)})) {
    maybe_implicitly_close_list_tag(parser, token, false);
    bool result = maybe_implicitly_close_p_tag(parser, token);
    insert_element_from_token(parser, token);
    return result;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_PLAINTEXT)) {
    bool result = maybe_implicitly_close_p_tag(parser, token);
    insert_element_from_token(parser, token);
    gumbo_tokenizer_set_state(parser, GUMBO_LEX_PLAINTEXT);
    return result;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_BUTTON)) {
    if (has_an_element_in_scope(parser, GUMBO_TAG_BUTTON)) {
      parser_add_parse_error(parser, token);
      implicitly_close_tags(
          parser, token, GUMBO_NAMESPACE_HTML, GUMBO_TAG_BUTTON);
      state->_reprocess_current_token = true;
      return false;
    }
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    state->_frameset_ok = false;
    return true;
  } else if (tag_in(token, kEndTag, (gumbo_tagset){
        TAG(ADDRESS), TAG(ARTICLE), TAG(ASIDE), TAG(BLOCKQUOTE), TAG(BUTTON),
        TAG(CENTER), TAG(DETAILS), TAG(DIALOG), TAG(DIR), TAG(DIV), TAG(DL),
        TAG(FIELDSET), TAG(FIGCAPTION), TAG(FIGURE), TAG(FOOTER), TAG(HEADER),
        TAG(HGROUP), TAG(LISTING), TAG(MAIN), TAG(MENU), TAG(NAV), TAG(OL),
        TAG(PRE), TAG(SECTION), TAG(SUMMARY), TAG(UL), TAG(SEARCH)}))
  {
    GumboTag tag = token->v.end_tag;
    if (!has_an_element_in_scope(parser, tag)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    implicitly_close_tags(
        parser, token, GUMBO_NAMESPACE_HTML, token->v.end_tag);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_FORM)) {
    if (has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      if (!has_an_element_in_scope(parser, GUMBO_TAG_FORM)) {
        parser_add_parse_error(parser, token);
        ignore_token(parser);
        return false;
      }
      bool success = true;
      generate_implied_end_tags(parser, GUMBO_TAG_LAST);
      if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_FORM)) {
        parser_add_parse_error(parser, token);
        return false;
      }
      while (!node_html_tag_is(pop_current_node(parser), GUMBO_TAG_FORM))
        ;
      return success;
    } else {
      bool result = true;
      GumboNode* node = state->_form_element;
      assert(!node || node->type == GUMBO_NODE_ELEMENT);
      state->_form_element = NULL;
      if (!node || !has_node_in_scope(parser, node)) {
        gumbo_debug("Closing an unopened form.\n");
        parser_add_parse_error(parser, token);
        ignore_token(parser);
        return false;
      }
      maybe_flush_text_node_buffer(parser);
      generate_implied_end_tags(parser, GUMBO_TAG_LAST);
      if (get_current_node(parser) == node) {
        record_end_of_element(token, &node->v.element);
      } else {
        parser_add_parse_error(parser, token);
        result = false;
      }

      GumboVector* open_elements = &state->_open_elements;
      int index = gumbo_vector_index_of(open_elements, node);
      assert(index >= 0);
      gumbo_vector_remove_at(parser, index, open_elements);
      return result;
    }
  } else if (tag_is(token, kEndTag, GUMBO_TAG_P)) {
    if (!has_an_element_in_button_scope(parser, GUMBO_TAG_P)) {
      parser_add_parse_error(parser, token);
      insert_element_of_tag_type(
          parser, GUMBO_TAG_P, GUMBO_INSERTION_CONVERTED_FROM_END_TAG);
      state->_reprocess_current_token = true;
      return false;
    }
    return implicitly_close_tags(
        parser, token, GUMBO_NAMESPACE_HTML, GUMBO_TAG_P);
  } else if (tag_is(token, kEndTag, GUMBO_TAG_LI)) {
    if (!has_an_element_in_list_scope(parser, GUMBO_TAG_LI)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    return implicitly_close_tags(
        parser, token, GUMBO_NAMESPACE_HTML, GUMBO_TAG_LI);
  } else if (tag_in(token, kEndTag, (gumbo_tagset){TAG(DD), TAG(DT)})) {
    assert(token->type == GUMBO_TOKEN_END_TAG);
    GumboTag token_tag = token->v.end_tag;
    if (!has_an_element_in_scope(parser, token_tag)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    return implicitly_close_tags(
        parser, token, GUMBO_NAMESPACE_HTML, token_tag);
  } else if (tag_in(token, kEndTag, (gumbo_tagset){TAG(H1), TAG(H2), TAG(H3),
                                        TAG(H4), TAG(H5), TAG(H6)})) {
    if (!has_an_element_in_scope_with_tagname(
            parser, 6, (GumboTag[]){GUMBO_TAG_H1, GUMBO_TAG_H2, GUMBO_TAG_H3,
                           GUMBO_TAG_H4, GUMBO_TAG_H5, GUMBO_TAG_H6})) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    } else {
      generate_implied_end_tags(parser, GUMBO_TAG_LAST);
      const GumboNode* current_node = get_current_node(parser);
      bool success = node_html_tag_is(current_node, token->v.end_tag);
      if (!success) {
        parser_add_parse_error(parser, token);
      }
      do {
        current_node = pop_current_node(parser);
      } while (!node_tag_in_set(
                   current_node, (gumbo_tagset){TAG(H1), TAG(H2), TAG(H3),
                                     TAG(H4), TAG(H5), TAG(H6)}));
      return success;
    }
  } else if (tag_is(token, kStartTag, GUMBO_TAG_A)) {
    bool success = true;
    int last_a;
    int has_matching_a = find_last_anchor_index(parser, &last_a);
    if (has_matching_a) {
      assert(has_matching_a == 1);
      parser_add_parse_error(parser, token);
      adoption_agency_algorithm(parser, token, GUMBO_TAG_A);
      if (find_last_anchor_index(parser, &last_a)) {
        void* last_element = gumbo_vector_remove_at(
            parser, last_a, &state->_active_formatting_elements);
        gumbo_vector_remove(parser, last_element, &state->_open_elements);
      }
      success = false;
    }
    reconstruct_active_formatting_elements(parser);
    add_formatting_element(parser, insert_element_from_token(parser, token));
    return success;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(B), TAG(BIG), TAG(CODE), TAG(EM), TAG(FONT),
                     TAG(I), TAG(S), TAG(SMALL), TAG(STRIKE), TAG(STRONG),
                     TAG(TT), TAG(U)})) {
    reconstruct_active_formatting_elements(parser);
    add_formatting_element(parser, insert_element_from_token(parser, token));
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_NOBR)) {
    bool result = true;
    reconstruct_active_formatting_elements(parser);
    if (has_an_element_in_scope(parser, GUMBO_TAG_NOBR)) {
      result = false;
      parser_add_parse_error(parser, token);
      adoption_agency_algorithm(parser, token, GUMBO_TAG_NOBR);
      reconstruct_active_formatting_elements(parser);
    }
    insert_element_from_token(parser, token);
    add_formatting_element(parser, get_current_node(parser));
    return result;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(A), TAG(B), TAG(BIG), TAG(CODE), TAG(EM),
                     TAG(FONT), TAG(I), TAG(NOBR), TAG(S), TAG(SMALL),
                     TAG(STRIKE), TAG(STRONG), TAG(TT), TAG(U)})) {
    return adoption_agency_algorithm(parser, token, token->v.end_tag);
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(APPLET), TAG(MARQUEE), TAG(OBJECT)})) {
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    add_formatting_element(parser, &kActiveFormattingScopeMarker);
    set_frameset_not_ok(parser);
    return true;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(APPLET), TAG(MARQUEE), TAG(OBJECT)})) {
    GumboTag token_tag = token->v.end_tag;
    if (!has_an_element_in_table_scope(parser, token_tag)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    implicitly_close_tags(parser, token, GUMBO_NAMESPACE_HTML, token_tag);
    clear_active_formatting_elements(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TABLE)) {
    if (get_document_node(parser)->v.document.doc_type_quirks_mode !=
        GUMBO_DOCTYPE_QUIRKS) {
      maybe_implicitly_close_p_tag(parser, token);
    }
    insert_element_from_token(parser, token);
    set_frameset_not_ok(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(AREA), TAG(BR), TAG(EMBED), TAG(IMG),
                     TAG(IMAGE), TAG(KEYGEN), TAG(WBR)})) {
    bool success = true;
    if (tag_is(token, kStartTag, GUMBO_TAG_IMAGE)) {
      success = false;
      parser_add_parse_error(parser, token);
      token->v.start_tag.tag = GUMBO_TAG_IMG;
    }
    reconstruct_active_formatting_elements(parser);
    GumboNode* node = insert_element_from_token(parser, token);
    if (tag_is(token, kStartTag, GUMBO_TAG_IMAGE)) {
      success = false;
      parser_add_parse_error(parser, token);
      node->v.element.tag = GUMBO_TAG_IMG;
      node->parse_flags |= GUMBO_INSERTION_FROM_IMAGE;
    }
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    set_frameset_not_ok(parser);
    return success;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_INPUT)) {
    if (!attribute_matches(&token->v.start_tag.attributes, "type", "hidden")) {
      set_frameset_not_ok(parser);
    }
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(PARAM), TAG(SOURCE), TAG(TRACK)})) {
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HR)) {
    bool result = maybe_implicitly_close_p_tag(parser, token);
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    set_frameset_not_ok(parser);
    return result;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_ISINDEX)) {
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    if (token->v.start_tag.is_self_closing) {
      pop_current_node(parser);
      acknowledge_self_closing_tag(parser);
    }
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TEXTAREA)) {
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_RCDATA);
    parser->_parser_state->_ignore_next_linefeed = true;
    set_frameset_not_ok(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_XMP)) {
    bool result = maybe_implicitly_close_p_tag(parser, token);
    reconstruct_active_formatting_elements(parser);
    set_frameset_not_ok(parser);
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_RAWTEXT);
    return result;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_IFRAME)) {
    set_frameset_not_ok(parser);
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_RAWTEXT);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_NOEMBED)) {
    run_generic_parsing_algorithm(parser, token, GUMBO_LEX_RAWTEXT);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_SELECT)) {
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    set_frameset_not_ok(parser);
    GumboInsertionMode state = parser->_parser_state->_insertion_mode;
    if (state == GUMBO_INSERTION_MODE_IN_TABLE ||
        state == GUMBO_INSERTION_MODE_IN_CAPTION ||
        state == GUMBO_INSERTION_MODE_IN_TABLE_BODY ||
        state == GUMBO_INSERTION_MODE_IN_ROW ||
        state == GUMBO_INSERTION_MODE_IN_CELL) {
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_SELECT_IN_TABLE);
    } else {
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_SELECT);
    }
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(OPTION), TAG(OPTGROUP)})) {
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTION)) {
      pop_current_node(parser);
    }
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(RB), TAG(RP), TAG(RT), TAG(RTC)})) {
    bool success = true;
    GumboTag exception =
        tag_in(token, kStartTag, (gumbo_tagset){TAG(RT), TAG(RP)})
            ? GUMBO_TAG_RTC
            : GUMBO_TAG_LAST;
    if (has_an_element_in_scope(parser, GUMBO_TAG_RUBY)) {
      generate_implied_end_tags(parser, exception);
    }
    if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_RUBY) &&
        !(exception == GUMBO_TAG_LAST ||
            node_html_tag_is(get_current_node(parser), GUMBO_TAG_RTC))) {
      parser_add_parse_error(parser, token);
      success = false;
    }
    insert_element_from_token(parser, token);
    return success;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_BR)) {
    parser_add_parse_error(parser, token);
    reconstruct_active_formatting_elements(parser);
    insert_element_of_tag_type(
        parser, GUMBO_TAG_BR, GUMBO_INSERTION_CONVERTED_FROM_END_TAG);
    pop_current_node(parser);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_MATH)) {
    reconstruct_active_formatting_elements(parser);
    adjust_mathml_attributes(parser, token);
    adjust_foreign_attributes(parser, token);
    insert_foreign_element(parser, token, GUMBO_NAMESPACE_MATHML);
    if (token->v.start_tag.is_self_closing) {
      pop_current_node(parser);
      acknowledge_self_closing_tag(parser);
    }
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_SVG)) {
    reconstruct_active_formatting_elements(parser);
    adjust_svg_attributes(parser, token);
    adjust_foreign_attributes(parser, token);
    insert_foreign_element(parser, token, GUMBO_NAMESPACE_SVG);
    if (token->v.start_tag.is_self_closing) {
      pop_current_node(parser);
      acknowledge_self_closing_tag(parser);
    }
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(CAPTION), TAG(COL), TAG(COLGROUP),
                     TAG(FRAME), TAG(HEAD), TAG(TBODY), TAG(TD), TAG(TFOOT),
                     TAG(TH), TAG(THEAD), TAG(TR)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_START_TAG) {
    reconstruct_active_formatting_elements(parser);
    insert_element_from_token(parser, token);
    return true;
  } else {
    assert(token->type == GUMBO_TOKEN_END_TAG);
    GumboTag end_tag = token->v.end_tag;
    assert(state->_open_elements.length > 0);
    assert(node_html_tag_is(state->_open_elements.data[0], GUMBO_TAG_HTML));
    for (int i = state->_open_elements.length; --i >= 0;) {
      const GumboNode* node = state->_open_elements.data[i];
      if (node_html_tag_is(node, end_tag)) {
        generate_implied_end_tags(parser, end_tag);
        while (node != pop_current_node(parser))
          ;
        return true;
      } else if (is_special_node(node)) {
        parser_add_parse_error(parser, token);
        ignore_token(parser);
        return false;
      }
    }
    assert(0);
    return false;
  }
}

static bool handle_text(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_CHARACTER ||
      token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
  } else {
    if (token->type == GUMBO_TOKEN_EOF) {
      parser_add_parse_error(parser, token);
      parser->_parser_state->_reprocess_current_token = true;
    }
    pop_current_node(parser);
    set_insertion_mode(parser, parser->_parser_state->_original_insertion_mode);
  }
  return true;
}

static bool handle_in_table(GumboParser* parser, GumboToken* token) {
  GumboParserState* state = parser->_parser_state;
  if (token->type == GUMBO_TOKEN_CHARACTER ||
      token->type == GUMBO_TOKEN_WHITESPACE) {
    assert(state->_text_node._buffer.length == 0);
    state->_original_insertion_mode = state->_insertion_mode;
    state->_reprocess_current_token = true;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_TEXT);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_CAPTION)) {
    clear_stack_to_table_context(parser);
    add_formatting_element(parser, &kActiveFormattingScopeMarker);
    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_CAPTION);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_COLGROUP)) {
    clear_stack_to_table_context(parser);
    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_COLUMN_GROUP);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_COL)) {
    clear_stack_to_table_context(parser);
    insert_element_of_tag_type(
        parser, GUMBO_TAG_COLGROUP, GUMBO_INSERTION_IMPLIED);
    parser->_parser_state->_reprocess_current_token = true;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_COLUMN_GROUP);
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(TBODY), TAG(TFOOT), TAG(THEAD), TAG(TD),
                     TAG(TH), TAG(TR)})) {
    clear_stack_to_table_context(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_BODY);
    if (tag_in(token, kStartTag, (gumbo_tagset){TAG(TD), TAG(TH), TAG(TR)})) {
      insert_element_of_tag_type(
          parser, GUMBO_TAG_TBODY, GUMBO_INSERTION_IMPLIED);
      state->_reprocess_current_token = true;
    } else {
      insert_element_from_token(parser, token);
    }
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TABLE)) {
    parser_add_parse_error(parser, token);
    if (close_table(parser)) {
      parser->_parser_state->_reprocess_current_token = true;
    } else {
      ignore_token(parser);
    }
    return false;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_TABLE)) {
    if (!close_table(parser)) {
      parser_add_parse_error(parser, token);
      return false;
    }
    return true;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(BODY), TAG(CAPTION), TAG(COL),
                     TAG(COLGROUP), TAG(HTML), TAG(TBODY), TAG(TD), TAG(TFOOT),
                     TAG(TH), TAG(THEAD), TAG(TR)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(STYLE), TAG(SCRIPT), TAG(TEMPLATE)}) ||
             (tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE))) {
    return handle_in_head(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_INPUT) &&
             attribute_matches(
                 &token->v.start_tag.attributes, "type", "hidden")) {
    parser_add_parse_error(parser, token);
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_FORM)) {
    parser_add_parse_error(parser, token);
    if (state->_form_element || has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      ignore_token(parser);
      return false;
    }
    state->_form_element = insert_element_from_token(parser, token);
    pop_current_node(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return handle_in_body(parser, token);
  } else {
    parser_add_parse_error(parser, token);
    state->_foster_parent_insertions = true;
    bool result = handle_in_body(parser, token);
    state->_foster_parent_insertions = false;
    return result;
  }
}

static bool handle_in_table_text(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_NULL) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_CHARACTER ||
             token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else {
    GumboParserState* state = parser->_parser_state;
    GumboStringBuffer* buffer = &state->_text_node._buffer;
    for (unsigned int i = 0; i < buffer->length; ++i) {
      if (!isspace((unsigned char) buffer->data[i]) ||
          buffer->data[i] == '\v') {
        state->_foster_parent_insertions = true;
        reconstruct_active_formatting_elements(parser);
        break;
      }
    }
    maybe_flush_text_node_buffer(parser);
    state->_foster_parent_insertions = false;
    state->_reprocess_current_token = true;
    state->_insertion_mode = state->_original_insertion_mode;
    return true;
  }
}

static bool handle_in_caption(GumboParser* parser, GumboToken* token) {
  if (tag_is(token, kEndTag, GUMBO_TAG_CAPTION)) {
    if (!has_an_element_in_table_scope(parser, GUMBO_TAG_CAPTION)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    } else {
      generate_implied_end_tags(parser, GUMBO_TAG_LAST);
      bool result = true;
      if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_CAPTION)) {
        parser_add_parse_error(parser, token);
      }
      while (!node_html_tag_is(pop_current_node(parser), GUMBO_TAG_CAPTION))
        ;
      clear_active_formatting_elements(parser);
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
      return result;
    }
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(CAPTION), TAG(COL), TAG(COLGROUP),
                     TAG(TBODY), TAG(TD), TAG(TFOOT), TAG(TH), TAG(THEAD),
                     TAG(TR)}) ||
             (tag_is(token, kEndTag, GUMBO_TAG_TABLE))) {
    if (!has_an_element_in_table_scope(parser, GUMBO_TAG_CAPTION)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    while (!node_html_tag_is(pop_current_node(parser), GUMBO_TAG_CAPTION))
      ;
    clear_active_formatting_elements(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(BODY), TAG(COL), TAG(COLGROUP), TAG(HTML),
                     TAG(TBODY), TAG(TD), TAG(TFOOT), TAG(TH), TAG(THEAD),
                     TAG(TR)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    return handle_in_body(parser, token);
  }
}

static bool handle_in_column_group(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_COL)) {
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_COLGROUP)) {
    if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_COLGROUP)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    pop_current_node(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    return false;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_COL)) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TEMPLATE) ||
             tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE)) {
    return handle_in_head(parser, token);
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return handle_in_body(parser, token);
  } else {
    if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_COLGROUP)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    pop_current_node(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  }
}

static bool handle_in_table_body(GumboParser* parser, GumboToken* token) {
  if (tag_is(token, kStartTag, GUMBO_TAG_TR)) {
    clear_stack_to_table_body_context(parser);
    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_ROW);
    return true;
  } else if (tag_in(token, kStartTag, (gumbo_tagset){TAG(TD), TAG(TH)})) {
    parser_add_parse_error(parser, token);
    clear_stack_to_table_body_context(parser);
    insert_element_of_tag_type(parser, GUMBO_TAG_TR, GUMBO_INSERTION_IMPLIED);
    parser->_parser_state->_reprocess_current_token = true;
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_ROW);
    return false;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(TBODY), TAG(TFOOT), TAG(THEAD)})) {
    if (!has_an_element_in_table_scope(parser, token->v.end_tag)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    clear_stack_to_table_body_context(parser);
    pop_current_node(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    return true;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(CAPTION), TAG(COL), TAG(COLGROUP),
                     TAG(TBODY), TAG(TFOOT), TAG(THEAD)}) ||
             tag_is(token, kEndTag, GUMBO_TAG_TABLE)) {
    if (!(has_an_element_in_table_scope(parser, GUMBO_TAG_TBODY) ||
            has_an_element_in_table_scope(parser, GUMBO_TAG_THEAD) ||
            has_an_element_in_table_scope(parser, GUMBO_TAG_TFOOT))) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    clear_stack_to_table_body_context(parser);
    pop_current_node(parser);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    parser->_parser_state->_reprocess_current_token = true;
    return true;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(BODY), TAG(CAPTION), TAG(COL), TAG(TR),
                     TAG(COLGROUP), TAG(HTML), TAG(TD), TAG(TH)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    return handle_in_table(parser, token);
  }
}

static bool handle_in_row(GumboParser* parser, GumboToken* token) {
  if (tag_in(token, kStartTag, (gumbo_tagset){TAG(TH), TAG(TD)})) {
    clear_stack_to_table_row_context(parser);
    insert_element_from_token(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_CELL);
    add_formatting_element(parser, &kActiveFormattingScopeMarker);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_TR)) {
    if (!has_an_element_in_table_scope(parser, GUMBO_TAG_TR)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    } else {
      clear_stack_to_table_row_context(parser);
      pop_current_node(parser);
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_BODY);
      return true;
    }
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(CAPTION), TAG(COL), TAG(COLGROUP),
                     TAG(TBODY), TAG(TFOOT), TAG(THEAD), TAG(TR)}) ||
             tag_is(token, kEndTag, GUMBO_TAG_TABLE)) {
    if (!has_an_element_in_table_scope(parser, GUMBO_TAG_TR)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    } else {
      clear_stack_to_table_row_context(parser);
      pop_current_node(parser);
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_BODY);
      parser->_parser_state->_reprocess_current_token = true;
      return true;
    }
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(TBODY), TAG(TFOOT), TAG(THEAD)})) {
    if (!has_an_element_in_table_scope(parser, token->v.end_tag) ||
        (!has_an_element_in_table_scope(parser, GUMBO_TAG_TR))) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    } else {
      clear_stack_to_table_row_context(parser);
      pop_current_node(parser);
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_BODY);
      parser->_parser_state->_reprocess_current_token = true;
      return true;
    }
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(BODY), TAG(CAPTION), TAG(COL),
                     TAG(COLGROUP), TAG(HTML), TAG(TD), TAG(TH)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else {
    return handle_in_table(parser, token);
  }
}

static bool handle_in_cell(GumboParser* parser, GumboToken* token) {
  if (tag_in(token, kEndTag, (gumbo_tagset){TAG(TD), TAG(TH)})) {
    GumboTag token_tag = token->v.end_tag;
    if (!has_an_element_in_table_scope(parser, token_tag)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    return close_table_cell(parser, token, token_tag);
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(CAPTION), TAG(COL), TAG(COLGROUP),
                     TAG(TBODY), TAG(TD), TAG(TFOOT), TAG(TH), TAG(THEAD),
                     TAG(TR)})) {
    gumbo_debug("Handling <td> in cell.\n");
    if (!has_an_element_in_table_scope(parser, GUMBO_TAG_TH) &&
        !has_an_element_in_table_scope(parser, GUMBO_TAG_TD)) {
      gumbo_debug("Bailing out because there's no <td> or <th> in scope.\n");
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    parser->_parser_state->_reprocess_current_token = true;
    return close_current_cell(parser, token);
  } else if (tag_in(token, kEndTag, (gumbo_tagset){TAG(BODY), TAG(CAPTION),
                                        TAG(COL), TAG(COLGROUP), TAG(HTML)})) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_in(token, kEndTag, (gumbo_tagset){TAG(TABLE), TAG(TBODY),
                                        TAG(TFOOT), TAG(THEAD), TAG(TR)})) {
    if (!has_an_element_in_table_scope(parser, token->v.end_tag)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    parser->_parser_state->_reprocess_current_token = true;
    return close_current_cell(parser, token);
  } else {
    return handle_in_body(parser, token);
  }
}

static bool handle_in_select(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_NULL) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_CHARACTER ||
             token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_OPTION)) {
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTION)) {
      pop_current_node(parser);
    }
    insert_element_from_token(parser, token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_OPTGROUP)) {
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTION)) {
      pop_current_node(parser);
    }
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTGROUP)) {
      pop_current_node(parser);
    }
    insert_element_from_token(parser, token);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HR)) {
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTION)) {
      pop_current_node(parser);
    }
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTGROUP)) {
      pop_current_node(parser);
    }
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_OPTGROUP)) {
    GumboVector* open_elements = &parser->_parser_state->_open_elements;
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTION) &&
        node_html_tag_is(open_elements->data[open_elements->length - 2],
            GUMBO_TAG_OPTGROUP)) {
      pop_current_node(parser);
    }
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTGROUP)) {
      pop_current_node(parser);
      return true;
    } else {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
  } else if (tag_is(token, kEndTag, GUMBO_TAG_OPTION)) {
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_OPTION)) {
      pop_current_node(parser);
      return true;
    } else {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
  } else if (tag_is(token, kEndTag, GUMBO_TAG_SELECT)) {
    if (!has_an_element_in_select_scope(parser, GUMBO_TAG_SELECT)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    close_current_select(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_SELECT)) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    if (has_an_element_in_select_scope(parser, GUMBO_TAG_SELECT)) {
      close_current_select(parser);
    }
    return false;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(INPUT), TAG(KEYGEN), TAG(TEXTAREA)})) {
    parser_add_parse_error(parser, token);
    if (!has_an_element_in_select_scope(parser, GUMBO_TAG_SELECT)) {
      ignore_token(parser);
    } else {
      close_current_select(parser);
      parser->_parser_state->_reprocess_current_token = true;
    }
    return false;
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(SCRIPT), TAG(TEMPLATE)}) ||
             tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE)) {
    return handle_in_head(parser, token);
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return handle_in_body(parser, token);
  } else {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  }
}

static bool handle_in_select_in_table(GumboParser* parser, GumboToken* token) {
  if (tag_in(token, kStartTag,
          (gumbo_tagset){TAG(CAPTION), TAG(TABLE), TAG(TBODY), TAG(TFOOT),
              TAG(THEAD), TAG(TR), TAG(TD), TAG(TH)})) {
    parser_add_parse_error(parser, token);
    close_current_select(parser);
    parser->_parser_state->_reprocess_current_token = true;
    return false;
  } else if (tag_in(token, kEndTag,
                 (gumbo_tagset){TAG(CAPTION), TAG(TABLE), TAG(TBODY),
                     TAG(TFOOT), TAG(THEAD), TAG(TR), TAG(TD), TAG(TH)})) {
    parser_add_parse_error(parser, token);
    if (!has_an_element_in_table_scope(parser, token->v.end_tag)) {
      ignore_token(parser);
      return false;
    } else {
      close_current_select(parser);
      parser->_parser_state->_reprocess_current_token = true;
      return false;
    }
  } else {
    return handle_in_select(parser, token);
  }
}

static bool handle_in_template(GumboParser* parser, GumboToken* token) {
  GumboParserState* state = parser->_parser_state;
  if (token->type == GUMBO_TOKEN_WHITESPACE ||
      token->type == GUMBO_TOKEN_CHARACTER ||
      token->type == GUMBO_TOKEN_COMMENT || token->type == GUMBO_TOKEN_NULL ||
      token->type == GUMBO_TOKEN_DOCTYPE) {
    return handle_in_body(parser, token);
  } else if (tag_in(token, kStartTag,
                 (gumbo_tagset){TAG(BASE), TAG(BASEFONT), TAG(BGSOUND),
                     TAG(LINK), TAG(META), TAG(NOFRAMES), TAG(SCRIPT),
                     TAG(STYLE), TAG(TEMPLATE), TAG(TITLE)}) ||
             tag_is(token, kEndTag, GUMBO_TAG_TEMPLATE)) {
    return handle_in_head(parser, token);
  } else if (tag_in(
                 token, kStartTag, (gumbo_tagset){TAG(CAPTION), TAG(COLGROUP),
                                       TAG(TBODY), TAG(TFOOT), TAG(THEAD)})) {
    pop_template_insertion_mode(parser);
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE);
    state->_reprocess_current_token = true;
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_COL)) {
    pop_template_insertion_mode(parser);
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_COLUMN_GROUP);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_COLUMN_GROUP);
    state->_reprocess_current_token = true;
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_TR)) {
    pop_template_insertion_mode(parser);
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_BODY);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TABLE_BODY);
    state->_reprocess_current_token = true;
    return true;
  } else if (tag_in(token, kStartTag, (gumbo_tagset){TAG(TD), TAG(TH)})) {
    pop_template_insertion_mode(parser);
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_ROW);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_ROW);
    state->_reprocess_current_token = true;
    return true;
  } else if (token->type == GUMBO_TOKEN_START_TAG) {
    pop_template_insertion_mode(parser);
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_BODY);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_BODY);
    state->_reprocess_current_token = true;
    return true;
  } else if (token->type == GUMBO_TOKEN_END_TAG) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (token->type == GUMBO_TOKEN_EOF) {
    if (!has_open_element(parser, GUMBO_TAG_TEMPLATE)) {
      return true;
    }
    parser_add_parse_error(parser, token);
    while (!node_html_tag_is(pop_current_node(parser), GUMBO_TAG_TEMPLATE))
      ;
    clear_active_formatting_elements(parser);
    pop_template_insertion_mode(parser);
    reset_insertion_mode_appropriately(parser);
    state->_reprocess_current_token = true;
    return false;
  } else {
    assert(0);
    return false;
  }
}

static bool handle_after_body(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_WHITESPACE ||
      tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    GumboNode* html_node = parser->_output->root;
    assert(html_node != NULL);
    append_comment_node(parser, html_node, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_HTML)) {
    if (is_fragment_parser(parser)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_AFTER_BODY);
    GumboNode* html = parser->_parser_state->_open_elements.data[0];
    assert(node_html_tag_is(html, GUMBO_TAG_HTML));
    record_end_of_element(
        parser->_parser_state->_current_token, &html->v.element);
    return true;
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return true;
  } else {
    parser_add_parse_error(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_BODY);
    parser->_parser_state->_reprocess_current_token = true;
    return false;
  }
}

static bool handle_in_frameset(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_is(token, kStartTag, GUMBO_TAG_FRAMESET)) {
    insert_element_from_token(parser, token);
    return true;
  } else if (tag_is(token, kEndTag, GUMBO_TAG_FRAMESET)) {
    if (node_html_tag_is(get_current_node(parser), GUMBO_TAG_HTML)) {
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    }
    pop_current_node(parser);
    if (!is_fragment_parser(parser) &&
        !node_html_tag_is(get_current_node(parser), GUMBO_TAG_FRAMESET)) {
      set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_FRAMESET);
    }
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_FRAME)) {
    insert_element_from_token(parser, token);
    pop_current_node(parser);
    acknowledge_self_closing_tag(parser);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_NOFRAMES)) {
    return handle_in_head(parser, token);
  } else if (token->type == GUMBO_TOKEN_EOF) {
    if (!node_html_tag_is(get_current_node(parser), GUMBO_TAG_HTML)) {
      parser_add_parse_error(parser, token);
      return false;
    }
    return true;
  } else {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  }
}

static bool handle_after_frameset(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_WHITESPACE) {
    insert_text_token(parser, token);
    return true;
  } else if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_current_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE) {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (tag_is(token, kEndTag, GUMBO_TAG_HTML)) {
    GumboNode* html = parser->_parser_state->_open_elements.data[0];
    assert(node_html_tag_is(html, GUMBO_TAG_HTML));
    record_end_of_element(
        parser->_parser_state->_current_token, &html->v.element);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_AFTER_AFTER_FRAMESET);
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_NOFRAMES)) {
    return handle_in_head(parser, token);
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return true;
  } else {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  }
}

static bool handle_after_after_body(GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_document_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE ||
             token->type == GUMBO_TOKEN_WHITESPACE ||
             tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return true;
  } else {
    parser_add_parse_error(parser, token);
    set_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_BODY);
    parser->_parser_state->_reprocess_current_token = true;
    return false;
  }
}

static bool handle_after_after_frameset(
    GumboParser* parser, GumboToken* token) {
  if (token->type == GUMBO_TOKEN_COMMENT) {
    append_comment_node(parser, get_document_node(parser), token);
    return true;
  } else if (token->type == GUMBO_TOKEN_DOCTYPE ||
             token->type == GUMBO_TOKEN_WHITESPACE ||
             tag_is(token, kStartTag, GUMBO_TAG_HTML)) {
    return handle_in_body(parser, token);
  } else if (token->type == GUMBO_TOKEN_EOF) {
    return true;
  } else if (tag_is(token, kStartTag, GUMBO_TAG_NOFRAMES)) {
    return handle_in_head(parser, token);
  } else {
    parser_add_parse_error(parser, token);
    ignore_token(parser);
    return false;
  }
}

typedef bool (*TokenHandler)(GumboParser* parser, GumboToken* token);
static const TokenHandler kTokenHandlers[] = {handle_initial,
    handle_before_html, handle_before_head, handle_in_head,
    handle_in_head_noscript, handle_after_head, handle_in_body, handle_text,
    handle_in_table, handle_in_table_text, handle_in_caption,
    handle_in_column_group, handle_in_table_body, handle_in_row, handle_in_cell,
    handle_in_select, handle_in_select_in_table, handle_in_template,
    handle_after_body, handle_in_frameset, handle_after_frameset,
    handle_after_after_body, handle_after_after_frameset};

static bool handle_html_content(GumboParser* parser, GumboToken* token) {
  return kTokenHandlers[(unsigned int) parser->_parser_state->_insertion_mode](
      parser, token);
}

static bool handle_in_foreign_content(GumboParser* parser, GumboToken* token) {
  gumbo_debug("Handling foreign content");
  switch (token->type) {
    case GUMBO_TOKEN_NULL:
      parser_add_parse_error(parser, token);
      token->v.character = kUtf8ReplacementChar;
      insert_text_token(parser, token);
      return false;
    case GUMBO_TOKEN_WHITESPACE:
      insert_text_token(parser, token);
      return true;
    case GUMBO_TOKEN_CDATA:
    case GUMBO_TOKEN_CHARACTER:
      insert_text_token(parser, token);
      set_frameset_not_ok(parser);
      return true;
    case GUMBO_TOKEN_COMMENT:
      append_comment_node(parser, get_current_node(parser), token);
      return true;
    case GUMBO_TOKEN_DOCTYPE:
      parser_add_parse_error(parser, token);
      ignore_token(parser);
      return false;
    default:
      break;
  }

  if (tag_in(token, kStartTag, (gumbo_tagset){
        TAG(B), TAG(BIG), TAG(BLOCKQUOTE), TAG(BODY), TAG(BR), TAG(CENTER),
        TAG(CODE), TAG(DD), TAG(DIV), TAG(DL), TAG(DT), TAG(EM), TAG(EMBED),
        TAG(H1), TAG(H2), TAG(H3), TAG(H4), TAG(H5), TAG(H6), TAG(HEAD),
        TAG(HR), TAG(I), TAG(IMG), TAG(LI), TAG(LISTING), TAG(MENU), TAG(META),
        TAG(NOBR), TAG(OL), TAG(P), TAG(PRE), TAG(RUBY), TAG(S), TAG(SMALL),
        TAG(SPAN), TAG(STRONG), TAG(STRIKE), TAG(SUB), TAG(SUP), TAG(TABLE),
        TAG(TT), TAG(U), TAG(UL), TAG(VAR)})
      || tag_in(token, kEndTag, (gumbo_tagset){TAG(BR), TAG(P)})
      || (tag_is(token, kStartTag, GUMBO_TAG_FONT)
        && (token_has_attribute(token, "color")
          || token_has_attribute(token, "face")
          || token_has_attribute(token, "size"))))
  {
    parser_add_parse_error(parser, token);

    while (!is_mathml_integration_point(get_current_node(parser))
        && !is_html_integration_point(get_current_node(parser))
        && get_current_node(parser)->v.element.tag_namespace != GUMBO_NAMESPACE_HTML)
    {
      pop_current_node(parser);
    }

    handle_html_content(parser, token);
    return false;
  }

  if (token->type == GUMBO_TOKEN_START_TAG) {
    const GumboNamespaceEnum current_namespace =
        get_adjusted_current_node(parser)->v.element.tag_namespace;
    if (current_namespace == GUMBO_NAMESPACE_MATHML) {
      adjust_mathml_attributes(parser, token);
    }
    if (current_namespace == GUMBO_NAMESPACE_SVG) {
      adjust_svg_attributes(parser, token);
    }
    adjust_foreign_attributes(parser, token);
    insert_foreign_element(parser, token, current_namespace);
    if (token->v.start_tag.is_self_closing) {
      pop_current_node(parser);
      acknowledge_self_closing_tag(parser);
    }
    return true;
  } else {
    assert(token->type == GUMBO_TOKEN_END_TAG);
    GumboNode* node = get_current_node(parser);
    assert(node != NULL);
    GumboStringPiece token_tagname = token->original_text;
    GumboStringPiece node_tagname = node->v.element.original_tag;
    gumbo_tag_from_original_text(&token_tagname);
    gumbo_tag_from_original_text(&node_tagname);

    bool is_success = true;
    if (!gumbo_string_equals_ignore_case(&node_tagname, &token_tagname)) {
      parser_add_parse_error(parser, token);
      is_success = false;
    }
    int i = parser->_parser_state->_open_elements.length;
    for (--i; i > 0;) {
      gumbo_debug("Foreign %.*s node at %d.\n", node_tagname.length,
          node_tagname.data, i);
      if (gumbo_string_equals_ignore_case(&node_tagname, &token_tagname)) {
        gumbo_debug("Matches.\n");
        while (pop_current_node(parser) != node) {
        }
        return is_success;
      }
      --i;
      node = parser->_parser_state->_open_elements.data[i];
      if (node->v.element.tag_namespace == GUMBO_NAMESPACE_HTML) {
        break;
      }
      node_tagname = node->v.element.original_tag;
      gumbo_tag_from_original_text(&node_tagname);
    }
    assert(node->v.element.tag_namespace == GUMBO_NAMESPACE_HTML);
    return handle_html_content(parser, token) && is_success;
  }
}

static bool handle_token(GumboParser* parser, GumboToken* token) {
  if (parser->_parser_state->_ignore_next_linefeed &&
      token->type == GUMBO_TOKEN_WHITESPACE && token->v.character == '\n') {
    parser->_parser_state->_ignore_next_linefeed = false;
    ignore_token(parser);
    return true;
  }
  parser->_parser_state->_ignore_next_linefeed = false;

  if (tag_is(token, kEndTag, GUMBO_TAG_BODY)) {
    parser->_parser_state->_closed_body_tag = true;
  }
  if (tag_is(token, kEndTag, GUMBO_TAG_HTML)) {
    parser->_parser_state->_closed_html_tag = true;
  }

  const GumboNode* current_node = get_adjusted_current_node(parser);
  assert(!current_node || current_node->type == GUMBO_NODE_ELEMENT ||
         current_node->type == GUMBO_NODE_TEMPLATE);
  if (current_node) {
    gumbo_debug("Current node: <%s>.\n",
        gumbo_normalized_tagname(current_node->v.element.tag));
  }
  if (!current_node ||
      current_node->v.element.tag_namespace == GUMBO_NAMESPACE_HTML ||
      (is_mathml_integration_point(current_node) &&
          (token->type == GUMBO_TOKEN_CHARACTER ||
              token->type == GUMBO_TOKEN_WHITESPACE ||
              token->type == GUMBO_TOKEN_NULL ||
              (token->type == GUMBO_TOKEN_START_TAG &&
                  !tag_in(token, kStartTag,
                      (gumbo_tagset){TAG(MGLYPH), TAG(MALIGNMARK)})))) ||
      (current_node->v.element.tag_namespace == GUMBO_NAMESPACE_MATHML &&
          node_qualified_tag_is(
              current_node, GUMBO_NAMESPACE_MATHML, GUMBO_TAG_ANNOTATION_XML) &&
          tag_is(token, kStartTag, GUMBO_TAG_SVG)) ||
      (is_html_integration_point(current_node) &&
          (token->type == GUMBO_TOKEN_START_TAG ||
              token->type == GUMBO_TOKEN_CHARACTER ||
              token->type == GUMBO_TOKEN_NULL ||
              token->type == GUMBO_TOKEN_WHITESPACE)) ||
      token->type == GUMBO_TOKEN_EOF) {
    return handle_html_content(parser, token);
  } else {
    return handle_in_foreign_content(parser, token);
  }
}

static void fragment_parser_init(GumboParser* parser, GumboTag fragment_ctx,
    GumboNamespaceEnum fragment_namespace) {
  GumboNode* root;
  assert(fragment_ctx != GUMBO_TAG_LAST);

  parser->_parser_state->_fragment_ctx = create_element(parser, fragment_ctx);
  parser->_parser_state->_fragment_ctx->v.element.tag_namespace =
      fragment_namespace;

  if (fragment_namespace == GUMBO_NAMESPACE_HTML) {
    switch (fragment_ctx) {
      case GUMBO_TAG_TITLE:
      case GUMBO_TAG_TEXTAREA:
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_RCDATA);
        break;

      case GUMBO_TAG_STYLE:
      case GUMBO_TAG_XMP:
      case GUMBO_TAG_IFRAME:
      case GUMBO_TAG_NOEMBED:
      case GUMBO_TAG_NOFRAMES:
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_RAWTEXT);
        break;

      case GUMBO_TAG_SCRIPT:
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_SCRIPT);
        break;

      case GUMBO_TAG_NOSCRIPT:
        break;

      case GUMBO_TAG_PLAINTEXT:
        gumbo_tokenizer_set_state(parser, GUMBO_LEX_PLAINTEXT);
        break;

      default:
        break;
    }
  }

  root = insert_element_of_tag_type(
      parser, GUMBO_TAG_HTML, GUMBO_INSERTION_IMPLIED);
  parser->_output->root = root;

  if (fragment_ctx == GUMBO_TAG_TEMPLATE) {
    push_template_insertion_mode(parser, GUMBO_INSERTION_MODE_IN_TEMPLATE);
  }

  reset_insertion_mode_appropriately(parser);
}

GumboOutput* gumbo_parse(const char* buffer) {
  return gumbo_parse_with_options(
      &kGumboDefaultOptions, buffer, strlen(buffer));
}

GumboOutput* gumbo_parse_with_options(
    const GumboOptions* options, const char* buffer, size_t length) {
  GumboParser parser;
  parser._options = options;
  output_init(&parser);
  gumbo_tokenizer_state_init(&parser, buffer, length);
  parser_state_init(&parser);

  if (options->fragment_context != GUMBO_TAG_LAST) {
    fragment_parser_init(
        &parser, options->fragment_context, options->fragment_namespace);
  }

  GumboParserState* state = parser._parser_state;
  gumbo_debug("Parsing %.*s.\n", length, buffer);

  int loop_count = 0;

  GumboToken token;
  bool has_error = false;

  do {
    if (state->_reprocess_current_token) {
      state->_reprocess_current_token = false;
    } else {
      GumboNode* current_node = get_current_node(&parser);
      gumbo_tokenizer_set_is_current_node_foreign(&parser,
          current_node &&
              current_node->v.element.tag_namespace != GUMBO_NAMESPACE_HTML);
      has_error = !gumbo_lex(&parser, &token) || has_error;
    }
    const char* token_type = "text";
    switch (token.type) {
      case GUMBO_TOKEN_DOCTYPE:
        token_type = "doctype";
        break;
      case GUMBO_TOKEN_START_TAG:
        token_type = gumbo_normalized_tagname(token.v.start_tag.tag);
        break;
      case GUMBO_TOKEN_END_TAG:
        token_type = gumbo_normalized_tagname(token.v.end_tag);
        break;
      case GUMBO_TOKEN_COMMENT:
        token_type = "comment";
        break;
      default:
        break;
    }
    gumbo_debug("Handling %s token @%d:%d in state %d.\n", (char*) token_type,
        token.position.line, token.position.column, state->_insertion_mode);

    state->_current_token = &token;
    state->_self_closing_flag_acknowledged =
        !(token.type == GUMBO_TOKEN_START_TAG &&
            token.v.start_tag.is_self_closing);

    has_error = !handle_token(&parser, &token) || has_error;

    assert(state->_reprocess_current_token ||
           token.type != GUMBO_TOKEN_START_TAG ||
           token.v.start_tag.attributes.data == NULL);

    if (!state->_self_closing_flag_acknowledged) {
      GumboError* error = parser_add_parse_error(&parser, &token);
      if (error) {
        error->type = GUMBO_ERR_UNACKNOWLEDGED_SELF_CLOSING_TAG;
      }
    }

    ++loop_count;
    assert(loop_count < 1000000000);

  } while ((token.type != GUMBO_TOKEN_EOF || state->_reprocess_current_token) &&
           !(options->stop_on_first_error && has_error));

  finish_parsing(&parser);
  GumboDocument* doc_type = &parser._output->document->v.document;
  if (doc_type->name == NULL) {
    doc_type->name = gumbo_copy_stringz(&parser, "");
  }
  if (doc_type->public_identifier == NULL) {
    doc_type->public_identifier = gumbo_copy_stringz(&parser, "");
  }
  if (doc_type->system_identifier == NULL) {
    doc_type->system_identifier = gumbo_copy_stringz(&parser, "");
  }

  parser_state_destroy(&parser);
  gumbo_tokenizer_state_destroy(&parser);
  return parser._output;
}

void gumbo_destroy_node(GumboOptions* options, GumboNode* node) {
  GumboParser parser;
  parser._options = options;
  destroy_node(&parser, node);
}

void gumbo_destroy_output(const GumboOptions* options, GumboOutput* output) {
  GumboParser parser;
  parser._options = options;
  destroy_node(&parser, output->document);
  for (unsigned int i = 0; i < output->errors.length; ++i) {
    gumbo_error_destroy(&parser, output->errors.data[i]);
  }
  gumbo_vector_destroy(&parser, &output->errors);
  gumbo_parser_deallocate(&parser, output);
}

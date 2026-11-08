/* Modifications Copyright (C) 2026 Ettacent */

#include "SyntaxHighlighter.h"
#include "LanguageTree.h"
#include "TokenList.h"

#include <algorithm>

SyntaxHighlighter::SyntaxHighlighter(const std::string &path) {
  m_tree = std::make_shared<LanguageTree>();
  m_tree->load(path);
}

TokenList SyntaxHighlighter::tokenize(const std::string &text,
                                      const std::string &language) const {
  const Grammar *grammar = m_tree->find(language);
  const Grammar *fixed = nullptr;

  if (!grammar) {
    const Grammar *host = nullptr;

    for (size_t split = language.find('-'); split != std::string::npos;
         split = language.find('-', split + 1)) {
      const Grammar *prefix = m_tree->find(language.substr(0, split));
      if (!prefix) {
        continue;
      }

      if (const Grammar *suffix = m_tree->find(language.substr(split + 1))) {
        grammar = prefix;
        fixed = suffix;
        break;
      }

      if (!host) {
        host = prefix;
      }
    }

    if (!grammar) {
      grammar = host;
    }
  }

  if (!grammar) {
    return TokenList(text);
  }

  constexpr size_t kSearchesPerByte = 64;
  constexpr size_t kSearchesAtLeast = 1024;

  const Grammar *named = nullptr;
  State state;
  state.searchesLeft = kSearchesPerByte * text.size() + kSearchesAtLeast;
  TokenList tokenList = tokenize(state, text, grammar);
  try {
    highlightEmbedded(state, tokenList, 0, text, fixed, named);
  } catch (...) {
  }
  return tokenList;
}

namespace {
constexpr std::string_view kEmbeddedLanguage = "embedded-language";
constexpr std::string_view kEmbeddedLanguageName = "embedded-language-name";

struct SliceCursor {
  const TokenList *list;
  TokenListPtr node;
  size_t pos;
};

TokenListPtr appendSlice(TokenList &out, TokenListPtr tail,
                         const TokenList &tokens, size_t pos, size_t from,
                         size_t to, std::string_view text, size_t offset,
                         std::vector<SliceCursor> &cursors, size_t depth) {
  if (cursors.size() == depth) {
    cursors.push_back({nullptr, nullptr, 0});
  }

  if (cursors[depth].list != &tokens) {
    cursors[depth] = {&tokens, tokens.head->next, pos};
  }

  TokenListPtr node = cursors[depth].node;
  pos = cursors[depth].pos;

  while (node != tokens.head && pos < to) {
    const size_t length = node->length();
    const size_t start = std::max(pos, from);
    const size_t end = std::min(pos + length, to);

    if (start < end) {
      if (node->isSyntax()) {
        const auto &syntax = static_cast<const Syntax &>(*node);

        TokenList children;
        appendSlice(children, children.head, syntax.children(), pos, start, end,
                    text, offset + (start - from), cursors, depth + 1);

        tail = out.addAfter(tail, syntax.type(), std::move(children),
                            syntax.alias(), end - start);
      } else {
        tail = out.addAfter(tail, text.substr(offset + (start - from), end - start));
      }
    }

    if (pos + length > to) {
      break;
    }

    pos += length;
    node = node->next;
  }

  cursors[depth].node = node;
  cursors[depth].pos = pos;
  return tail;
}
}

void SyntaxHighlighter::highlightEmbedded(State &state, TokenList &tokenList,
                                          size_t pos, std::string_view text,
                                          const Grammar *fixed,
                                          const Grammar *&named) const {
  std::string content;
  std::vector<EmbeddedPart> parts;
  const Grammar *language = nullptr;

  for (TokenListPtr node = tokenList.head->next; node != tokenList.head;
       pos += node->length(), node = node->next) {
    if (!node->isSyntax()) {
      continue;
    }

    auto &syntax = static_cast<Syntax &>(*node);

    if (syntax.type() == kEmbeddedLanguageName) {
      if (!fixed) {
        std::string name(text.substr(pos, syntax.length()));
        for (char &c : name) {
          if (c >= 'A' && c <= 'Z') {
            c += 'a' - 'A';
          }
        }

        named = m_tree->find(name);
      }

      highlightEmbedded(state, syntax.m_children, pos, text, fixed, named);
    } else if (syntax.type() == kEmbeddedLanguage) {
      const Grammar *grammar = fixed ? fixed : named;
      if (grammar != language) {
        highlightParts(state, parts, content, text, language);
        language = grammar;
      }

      if (language) {
        parts.push_back({node, pos, content.size()});
        content.append(text.substr(pos, syntax.length()));
      }
    } else {
      highlightEmbedded(state, syntax.m_children, pos, text, fixed, named);
    }
  }

  highlightParts(state, parts, content, text, language);
}

void SyntaxHighlighter::highlightParts(State &state,
                                       std::vector<EmbeddedPart> &parts,
                                       std::string &content,
                                       std::string_view text,
                                       const Grammar *language) const {
  if (parts.empty()) {
    return;
  }

  TokenList highlighted = tokenize(state, std::string_view(content), language);
  std::vector<SliceCursor> cursors;

  for (const auto &part : parts) {
    auto &syntax = static_cast<Syntax &>(*part.node);

    TokenList children;
    appendSlice(children, children.head, highlighted, 0, part.start,
                part.start + syntax.length(), text, part.offset, cursors, 0);

    syntax.m_children.swap(children);
  }

  parts.clear();
  content.clear();
}

std::map<std::string, std::string> SyntaxHighlighter::languages() const {
  return m_tree->keys();
}

std::string SyntaxHighlighter::languageName(const std::string &language) const {
  return m_tree->languageName(language);
}

std::vector<LanguageGroup> SyntaxHighlighter::languageGroups() const {
  std::vector<LanguageGroup> groups;
  std::map<std::pair<size_t, std::string>, size_t> index;

  for (const auto &name : m_tree->order()) {
    const auto &language = m_tree->language(name);
    if (language.first.empty()) {
      continue;
    }

    auto key = std::make_pair(language.second, language.first);
    auto found = index.find(key);

    if (found != index.end()) {
      groups[found->second].aliases.push_back(name);
    } else {
      index.emplace(key, groups.size());
      groups.push_back(LanguageGroup{name, language.first, {}});
    }
  }

  return groups;
}

TokenList SyntaxHighlighter::tokenize(State &state, std::string_view text,
                                      const Grammar *grammar) const {
  constexpr auto kMaxDepth = 32;
  if (state.depth >= kMaxDepth) {
    return TokenList(text);
  }
  struct DepthGuard {
    int &depth;
    DepthGuard(int &depth) : depth(++depth) {}
    ~DepthGuard() { --depth; }
  } guard(state.depth);

  const size_t searchesFrom = state.searchesFrom;
  state.searchesFrom = state.searches.size();

  TokenList tokenList(text);
  try {
    matchGrammar(state, text, tokenList, grammar, tokenList.head, 0, nullptr);
  } catch (...) {
  }

  state.searches.resize(state.searchesFrom);
  state.searchesFrom = searchesFrom;

  return tokenList;
}

bool SyntaxHighlighter::search(State &state, const Pattern &pattern, size_t pos,
                               std::string_view text, size_t &start,
                               size_t &length,
                               size_t &lookbehindLength) const {
  try {
    return pattern.search(pos, text, start, length, lookbehindLength);
  } catch (...) {
    if (--state.searchesToGiveUp == 0) {
      state.searchesLeft = 0;
    }
    return false;
  }
}

std::string_view SyntaxHighlighter::matchGreedy(State &state,
                                                const Pattern &pattern,
                                                bool &success, size_t &pos,
                                                std::string_view text) const {
  const auto end = state.searches.end();
  auto search = std::find_if(
      state.searches.begin() + state.searchesFrom, end,
      [&](const GreedySearch &search) { return search.pattern == &pattern; });

  if (search == end || pos < search->from ||
      (search->found && pos > search->start)) {
    if (search == end) {
      search = state.searches.insert(end, GreedySearch{&pattern});
    }

    --state.searchesLeft;
    search->from = pos;
    search->found = this->search(state, pattern, pos, text, search->start,
                                 search->length, search->lookbehind);
  }

  success = search->found;
  if (!success) {
    return {};
  }

  pos = search->start + search->lookbehind;
  return text.substr(pos, search->length - search->lookbehind);
}

void SyntaxHighlighter::matchGrammar(State &state, std::string_view text,
                                     TokenList &tokenList,
                                     const Grammar *grammar,
                                     TokenListPtr startNode, size_t startPos,
                                     RematchOptions *rematch) const {
  for (const auto &token : grammar->tokens) {
    int x = 0;
    for (auto j = token.cbegin(); j != token.cend(); ++j) {
      if (rematch && rematch->j == x && rematch->token == token.name()) {
        return;
      }

      const auto &pattern = *j->get();
      const auto &inside = pattern.inside();
      const bool greedy = pattern.greedy();

      size_t pos = startPos;

      for (TokenListPtr currentNode = startNode->next;
           currentNode != tokenList.head;
           pos += currentNode->length(), currentNode = currentNode->next) {
        if (rematch && pos >= rematch->reach) {
          break;
        }

        if (state.searchesLeft == 0) {
          return;
        }

        if (tokenList.length > text.length()) {
          return;
        }

        if (currentNode->isSyntax()) {
          continue;
        }

        const auto &currentText = dynamic_cast<Text &>(*currentNode);
        std::string_view str = currentText.value();

        auto removeCount = 1;
        std::string_view match;
        bool matchSuccess = false;
        size_t matchIndex = pos;

        if (greedy) {
          match = matchGreedy(state, pattern, matchSuccess, matchIndex, text);
          if (!matchSuccess || matchIndex >= text.length()) {
            break;
          }

          auto from = matchIndex;
          auto to = matchIndex + match.length();
          auto p = pos;

          p += currentNode->length();
          while (from >= p) {
            currentNode = currentNode->next;
            p += currentNode->length();
          }
          p -= currentNode->length();
          pos = p;

          if (currentNode->isSyntax()) {
            continue;
          }

          for (TokenListPtr k = currentNode;
               k != tokenList.head && (p < to || !k->isSyntax()); k = k->next) {
            removeCount++;
            p += k->length();
          }
          removeCount--;

          str = text.substr(pos, p - pos);
          matchIndex -= pos;
        } else {
          --state.searchesLeft;
          size_t start, length, lookbehindLength;
          if (!search(state, pattern, 0, str, start, length, lookbehindLength)) {
            continue;
          }

          matchIndex = start + lookbehindLength;
          match = str.substr(matchIndex, length - lookbehindLength);
        }

        auto from = matchIndex;
        auto before = str.substr(0, from);
        auto after = str.substr(from + match.length());

        auto reach = pos + str.length();
        if (rematch && reach > rematch->reach) {
          rematch->reach = reach;
        }

        TokenListPtr removeFrom = currentNode->prev;

        if (before.size()) {
          removeFrom = tokenList.addAfter(removeFrom, before);
          pos += before.length();
        }

        tokenList.removeRange(removeFrom, removeCount);

        TokenList tokenEntries = [&]() {
          if (inside) {
            return tokenize(state, match, inside);
          } else {
            return TokenList(match);
          }
        }();

        currentNode = tokenList.addAfter(removeFrom, token.name(),
                                         std::move(tokenEntries),
                                         pattern.alias(), match.size());

        if (after.size()) {
          tokenList.addAfter(currentNode, after);
        }

        if (removeCount > 1) {
          RematchOptions nestedRematch = {
              .token = token.name(), .reach = reach, .j = x};

          matchGrammar(state, text, tokenList, grammar, currentNode->prev, pos,
                       &nestedRematch);

          if (rematch && nestedRematch.reach > rematch->reach) {
            rematch->reach = nestedRematch.reach;
          }
        }
      }

      ++x;
    }
  }
}

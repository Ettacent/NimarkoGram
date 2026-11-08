/* Modifications Copyright (C) 2026 Ettacent */

#pragma once
#include <sstream>

#include "TokenList.h"
#include <vector>
#include <map>
#include <optional>

class LanguageTree;
class Pattern;
struct Grammar;

struct LanguageGroup
{
    std::string id;
    std::string name;
    std::vector<std::string> aliases;
};

struct RematchOptions
{
    std::string token;
    size_t reach;
    int j;
};

class SyntaxHighlighter
{
public:
    SyntaxHighlighter(const std::string& languages);

    TokenList tokenize(const std::string& text, const std::string& language) const;

    std::map<std::string, std::string> languages() const;

    std::string languageName(const std::string& language) const;

    std::vector<LanguageGroup> languageGroups() const;

private:
    struct EmbeddedPart
    {
        TokenListPtr node;
        size_t offset;
        size_t start;
    };

    struct GreedySearch
    {
        const Pattern* pattern;
        size_t from = 0;
        bool found = false;
        size_t start = 0;
        size_t length = 0;
        size_t lookbehind = 0;
    };

    struct State
    {
        int depth = 0;
        size_t searchesLeft = 0;
        int searchesToGiveUp = 2;

        std::vector<GreedySearch> searches;
        size_t searchesFrom = 0;
    };

    TokenList tokenize(State& state, std::string_view text, const Grammar* grammar) const;
    bool search(State& state, const Pattern& pattern, size_t pos, std::string_view text, size_t& start, size_t& length, size_t& lookbehindLength) const;
    std::string_view matchGreedy(State& state, const Pattern& pattern, bool& success, size_t& pos, std::string_view text) const;
    void highlightEmbedded(State& state, TokenList& tokenList, size_t pos, std::string_view text, const Grammar* fixed, const Grammar*& named) const;
    void highlightParts(State& state, std::vector<EmbeddedPart>& parts, std::string& content, std::string_view text, const Grammar* language) const;
    void matchGrammar(State& state, std::string_view text, TokenList& tokenList, const Grammar* grammar, TokenListPtr startNode, size_t startPos, RematchOptions* rematch) const;

    std::shared_ptr<LanguageTree> m_tree;
};
/* Modifications Copyright (C) 2026 Ettacent */

/* Copyright 2012 Mozilla Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* Modifications Copyright (C) 2026 Ettacent.
 * Text span conversion: Copyright 2021 Mozilla Foundation, Apache-2.0.
 * Full Apache license: LICENSE_PDFJS_APACHE_2.txt. */
import { getNormalizeWithNFKC } from "./mozilla/pdf_find_utils.mjs";
function binarySearchFirstItem(items, condition, start = 0) {
  let lo = start, hi = items.length;
  while (lo < hi) { const mid = (lo + hi) >>> 1; if (condition(items[mid])) hi = mid; else lo = mid + 1; }
  return lo;
}
const CHARACTERS_TO_NORMALIZE = new Map([
  ["\u2010", "-"],
  ["\u2018", "'"],
  ["\u2019", "'"],
  ["\u201A", "'"],
  ["\u201B", "'"],
  ["\u201C", '"'],
  ["\u201D", '"'],
  ["\u201E", '"'],
  ["\u201F", '"'],
  ["\u00BC", "1/4"],
  ["\u00BD", "1/2"],
  ["\u00BE", "3/4"],
]);

const DIACRITICS_EXCEPTION = new Set([
  0x3099, 0x309a,
  0x094d, 0x09cd, 0x0a4d, 0x0acd, 0x0b4d, 0x0bcd, 0x0c4d, 0x0ccd, 0x0d3b,
  0x0d3c, 0x0d4d, 0x0dca, 0x0e3a, 0x0eba, 0x0f84, 0x1039, 0x103a, 0x1714,
  0x1734, 0x17d2, 0x1a60, 0x1b44, 0x1baa, 0x1bab, 0x1bf2, 0x1bf3, 0x2d7f,
  0xa806, 0xa82c, 0xa8c4, 0xa953, 0xa9c0, 0xaaf6, 0xabed,
  0x0c56,
  0x0f71,
  0x0f72, 0x0f7a, 0x0f7b, 0x0f7c, 0x0f7d, 0x0f80,
  0x0f74,
]);
let DIACRITICS_EXCEPTION_STR;

const DIACRITICS_REG_EXP = /\p{M}+/gu;
const SPECIAL_CHARS_REG_EXP = /([+^$|])|(\p{P}+)|(\s+)|(\p{M})|(\p{L})/gu;

const SYLLABLES_REG_EXP = /[\uAC00-\uD7AF\uFA6C\uFACF-\uFAD1\uFAD5-\uFAD7]+/g;
const SYLLABLES_LENGTHS = new Map();
const FIRST_CHAR_SYLLABLES_REG_EXP =
  "[\\u1100-\\u1112\\ud7a4-\\ud7af\\ud84a\\ud84c\\ud850\\ud854\\ud857\\ud85f]";

const NFKC_CHARS_TO_NORMALIZE = new Map();

let noSyllablesRegExp = null;
let withSyllablesRegExp = null;

function normalize(text, options = {}) {

  const syllablePositions = [];
  let m;
  while ((m = SYLLABLES_REG_EXP.exec(text)) !== null) {
    let { index } = m;
    for (const char of m[0]) {
      let len = SYLLABLES_LENGTHS.get(char);
      if (!len) {
        len = char.normalize("NFD").length;
        SYLLABLES_LENGTHS.set(char, len);
      }
      syllablePositions.push([len, index++]);
    }
  }

  const hasSyllables = syllablePositions.length > 0;
  const ignoreDashEOL = options.ignoreDashEOL ?? false;

  let normalizationRegex;
  if (!hasSyllables && noSyllablesRegExp) {
    normalizationRegex = noSyllablesRegExp;
  } else if (hasSyllables && withSyllablesRegExp) {
    normalizationRegex = withSyllablesRegExp;
  } else {
    const replace = CHARACTERS_TO_NORMALIZE.keys().join("");
    const toNormalizeWithNFKC = getNormalizeWithNFKC();

    const CJK = "(?:\\p{Ideographic}|[\u3040-\u30FF])";
    const HKDiacritics = "(?:\u3099|\u309A)";
    const BrokenWord = `\\p{Ll}-\\n(?=\\p{Ll})|\\p{Lu}-\\n(?=\\p{L})`;

    const regexps = [
        `[${replace}]`,
        `[${toNormalizeWithNFKC}]`,
        `${HKDiacritics}\\n`,
        "\\p{M}+(?:-\\n)?",
        `${BrokenWord}`,
        "\\S-\\n",
        `${CJK}\\n`,
        "\\n",
        hasSyllables
        ? FIRST_CHAR_SYLLABLES_REG_EXP
        :
          "\\u0000",
    ];
    normalizationRegex = new RegExp(
      regexps.map(r => `(${r})`).join("|"),
      "gmu"
    );

    if (hasSyllables) {
      withSyllablesRegExp = normalizationRegex;
    } else {
      noSyllablesRegExp = normalizationRegex;
    }
  }



  const rawDiacriticsPositions = [];
  while ((m = DIACRITICS_REG_EXP.exec(text)) !== null) {
    rawDiacriticsPositions.push([m[0].length, m.index]);
  }

  let normalized = text.normalize("NFD");
  const positions = [0, 0];
  let rawDiacriticsIndex = 0;
  let syllableIndex = 0;
  let shift = 0;
  let shiftOrigin = 0;
  let eol = 0;
  let hasDiacritics = false;

  normalized = normalized.replace(
    normalizationRegex,
    (match, p1, p2, p3, p4, p5, p6, p7, p8, p9, i) => {
      i -= shiftOrigin;
      if (p1) {
        const replacement = CHARACTERS_TO_NORMALIZE.get(p1);
        const jj = replacement.length;
        for (let j = 1; j < jj; j++) {
          positions.push(i - shift + j, shift - j);
        }
        shift -= jj - 1;
        return replacement;
      }

      if (p2) {
        const replacement = NFKC_CHARS_TO_NORMALIZE.getOrInsertComputed(
          p2,
          () => p2.normalize("NFKC")
        );
        const jj = replacement.length;
        for (let j = 1; j < jj; j++) {
          positions.push(i - shift + j, shift - j);
        }
        shift -= jj - 1;
        return replacement;
      }

      if (p3) {
        hasDiacritics = true;

        if (i + eol === rawDiacriticsPositions[rawDiacriticsIndex]?.[1]) {
          ++rawDiacriticsIndex;
        } else {
          positions.push(i - 1 - shift + 1, shift - 1);
          shift -= 1;
          shiftOrigin += 1;
        }

        positions.push(i - shift + 1, shift);
        shiftOrigin += 1;
        eol += 1;

        return p3.charAt(0);
      }

      if (p4) {
        const hasTrailingDashEOL = p4.endsWith("\n");
        const len = hasTrailingDashEOL ? p4.length - 2 : p4.length;

        hasDiacritics = true;
        let jj = len;
        if (i + eol === rawDiacriticsPositions[rawDiacriticsIndex]?.[1]) {
          jj -= rawDiacriticsPositions[rawDiacriticsIndex][0];
          ++rawDiacriticsIndex;
        }

        for (let j = 1; j <= jj; j++) {
          positions.push(i - 1 - shift + j, shift - j);
        }
        shift -= jj;
        shiftOrigin += jj;

        if (hasTrailingDashEOL) {
          i += len - 1;
          positions.push(i - shift + 1, 1 + shift);
          shift += 1;
          shiftOrigin += 1;
          eol += 1;
          return p4.slice(0, len);
        }

        return p4;
      }

      if (p5) {
        if (ignoreDashEOL) {
          shiftOrigin += 1;
          eol += 1;
          return p5.slice(0, -1);
        }
        const len = p5.length - 2;
        positions.push(i - shift + len, 1 + shift);
        shift += 1;
        shiftOrigin += 1;
        eol += 1;
        return p5.slice(0, -2);
      }

      if (p6) {
        shiftOrigin += 1;
        eol += 1;
        return p6.slice(0, -1);
      }

      if (p7) {
        const len = p7.length - 1;
        positions.push(i - shift + len, shift);
        shiftOrigin += 1;
        eol += 1;
        return p7.slice(0, -1);
      }

      if (p8) {
        positions.push(i - shift + 1, shift - 1);
        shift -= 1;
        shiftOrigin += 1;
        eol += 1;
        return " ";
      }

      if (i + eol === syllablePositions[syllableIndex]?.[1]) {
        const newCharLen = syllablePositions[syllableIndex][0] - 1;
        ++syllableIndex;
        for (let j = 1; j <= newCharLen; j++) {
          positions.push(i - (shift - j), shift - j);
        }
        shift -= newCharLen;
        shiftOrigin += newCharLen;
      }
      return p9;
    }
  );

  positions.push(normalized.length, shift);
  const starts = new Uint32Array(positions.length >> 1);
  const shifts = new Int32Array(positions.length >> 1);
  for (let i = 0, ii = positions.length; i < ii; i += 2) {
    starts[i >> 1] = positions[i];
    shifts[i >> 1] = positions[i + 1];
  }

  return [normalized, [starts, shifts], hasDiacritics];
}

function getOriginalIndex(diffs, pos, len) {
  if (!diffs) {
    return [pos, len];
  }

  const [starts, shifts] = diffs;
  const start = pos;
  const end = pos + len - 1;
  let i = binarySearchFirstItem(starts, x => x >= start);
  if (starts[i] > start) {
    --i;
  }

  let j = binarySearchFirstItem(starts, x => x >= end, i);
  if (starts[j] > end) {
    --j;
  }

  const oldStart = start + shifts[i];

  const oldEnd = end + shifts[j];
  const oldLen = oldEnd + 1 - oldStart;

  return [oldStart, oldLen];
}

function queryPattern(query, hasDiacritics, matchDiacritics = false) {
    let isUnicode = false;
    const addExtraWhitespaces = (original, fixed) => {
      if (original === query) {
        return fixed;
      }
      if (query.startsWith(original)) {
        return `${fixed}[ ]*`;
      }
      if (query.endsWith(original)) {
        return `[ ]*${fixed}`;
      }
      return `[ ]*${fixed}[ ]*`;
    };
    query = query.replaceAll(
      SPECIAL_CHARS_REG_EXP,
      (
        match,
        p1  ,
        p2  ,
        p3  ,
        p4  ,
        p5
      ) => {

        if (p1) {
          return addExtraWhitespaces(p1, RegExp.escape(p1));
        }
        if (p2) {
          return addExtraWhitespaces(p2, RegExp.escape(p2));
        }
        if (p3) {
          return "[ ]+";
        }
        if (matchDiacritics) {
          return p4 || p5;
        }

        if (p4) {
          return DIACRITICS_EXCEPTION.has(p4.charCodeAt(0)) ? p4 : "";
        }

        if (hasDiacritics) {
          isUnicode = true;
          return `${p5}\\p{M}*`;
        }
        return p5;
      }
    );

    const trailingSpaces = "[ ]*";
    if (query.endsWith(trailingSpaces)) {
      query = query.slice(0, query.length - trailingSpaces.length);
    }

    if (matchDiacritics) {
      if (hasDiacritics) {
        DIACRITICS_EXCEPTION_STR ||= String.fromCharCode(
          ...DIACRITICS_EXCEPTION
        );

        isUnicode = true;
        query = `${query}(?=[${DIACRITICS_EXCEPTION_STR}]|[^\\p{M}]|$)`;
      }
    }

    return [isUnicode, query];
  }

function convertMatches(textContentItemsStr, matches, matchesLength) {
    if (!matches) {
      return [];
    }

    let i = 0,
      iIndex = 0;
    const end = textContentItemsStr.length - 1;
    const result = [];

    for (let m = 0, mm = matches.length; m < mm; m++) {
      let matchIdx = matches[m];

      while (i !== end && matchIdx >= iIndex + textContentItemsStr[i].length) {
        iIndex += textContentItemsStr[i].length;
        i++;
      }

      if (i === textContentItemsStr.length) {
        console.error("Could not find a matching mapping");
      }

      const match = {
        begin: {
          divIdx: i,
          offset: matchIdx - iIndex,
        },
      };

      matchIdx += matchesLength[m];

      while (i !== end && matchIdx > iIndex + textContentItemsStr[i].length) {
        iIndex += textContentItemsStr[i].length;
        i++;
      }

      match.end = {
        divIdx: i,
        offset: matchIdx - iIndex,
      };
      result.push(match);
    }
    return result;
  }


export { normalize, getOriginalIndex, queryPattern, convertMatches };

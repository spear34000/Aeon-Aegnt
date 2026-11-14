/**
 * Patterns that remove everything that is not a letter, a digit or (for the first one) whitespace.
 *
 * They use Unicode property escapes (`\p{L}`), which the Node.js that runs inside the Android app (nodejs-mobile) cannot
 * parse because it is built without ICU: a single such regular expression stops the whole server from loading there. So
 * the property version is tried first, and where it does not exist a range version takes over: ASCII, Latin-1 and general
 * punctuation, symbols, arrows, CJK punctuation, full-width punctuation and astral characters (emoji) are removed;
 * Hangul, kana, ideographs and the letters of other scripts stay. Where ICU exists nothing changes.
 */
const NON_WORD_RANGES =
  "\\u0000-\\u0008\\u000E-\\u001F\\u0021-\\u002F\\u003A-\\u0040\\u005B-\\u0060\\u007B-\\u00BF\\u00D7\\u00F7" +
  "\\u2010-\\u2027\\u2030-\\u205E\\u20A0-\\u20CF\\u2190-\\u2BFF\\u3001-\\u303F\\uFE30-\\uFE4F" +
  "\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65\\uD800-\\uDFFF";

/**
 * @param withProperties false builds the range version directly (what a Node without ICU gets), so it can be tested on a
 * Node that has ICU.
 */
export function buildNotWord(keepWhitespace: boolean, withProperties = true): RegExp {
  try {
    if (!withProperties) throw new SyntaxError("property escapes left out on purpose");
    return new RegExp(keepWhitespace ? "[^\\p{L}\\p{N}\\s]" : "[^\\p{L}\\p{N}]", "gu");
  } catch {
    return new RegExp(`[${NON_WORD_RANGES}${keepWhitespace ? "" : "\\s"}]`, "g");
  }
}

/** Matches each character that is not a letter, a digit or whitespace. Use with `replace`. */
export const NOT_WORD_OR_SPACE: RegExp = buildNotWord(true);
/** Matches each character that is not a letter or a digit (whitespace included). Use with `replace`. */
export const NOT_WORD: RegExp = buildNotWord(false);

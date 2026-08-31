package io.amscotti.janus.provider;

/**
 * Minimal structural JSON probing shared by the adapters (package-private). The adapters
 * must recognize upstream error envelopes without pulling Jackson into janus-provider
 * (module boundary, AGENTS.md; the codec is the only JSON decoder the adapter trusts for
 * chunk/response payloads). These helpers are a deliberately minimal structural probe:
 * string-aware, depth-aware member lookup over the members error classification needs.
 * They are not a JSON parser — values are returned as raw text and only ASCII type-name
 * comparison is performed.
 */
final class JsonProbe {

    private JsonProbe() {}

    /**
     * Raw JSON text of the top-level member {@code member} in {@code json}, or null when
     * {@code json} is not an object or the member is absent.
     */
    static String jsonMemberValue(String json, String member) {
        String s = json.stripLeading();
        if (!s.startsWith("{")) {
            return null;
        }
        int i = 1;
        while (true) {
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) == '}') {
                return null;
            }
            if (s.charAt(i) != '"') {
                i = skipToCommaOrBrace(s, i);
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                }
                continue;
            }
            int keyStart = i + 1;
            i = stringEnd(s, i);
            if (i < 0) {
                return null; // unterminated key — malformed object
            }
            String key = s.substring(keyStart, i);
            i = skipWs(s, i + 1);
            if (i >= s.length() || s.charAt(i) != ':') {
                i = skipToCommaOrBrace(s, i);
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                }
                continue;
            }
            i = skipWs(s, i + 1);
            if (key.equals(member)) {
                int end = valueEnd(s, i);
                return end < 0 ? null : s.substring(i, end);
            }
            i = valueEnd(s, i);
            if (i < 0) {
                return null; // malformed value — cannot scan further
            }
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
            }
        }
    }

    /** String value of a member, quotes stripped (no unescaping — ASCII type names only). */
    static String jsonStringMember(String json, String member) {
        String value = jsonMemberValue(json, member);
        if (value == null || value.length() < 2 || value.charAt(0) != '"') {
            return null;
        }
        return value.substring(1, value.length() - 1);
    }

    static Integer jsonNumberMember(String json, String member) {
        String value = jsonMemberValue(json, member);
        if (value == null) {
            return null;
        }
        String candidate = value;
        if (candidate.length() >= 2 && candidate.charAt(0) == '"' && candidate.charAt(candidate.length() - 1) == '"') {
            candidate = candidate.substring(1, candidate.length() - 1); // some upstreams quote the status
        }
        try {
            return Integer.valueOf(candidate);
        } catch (NumberFormatException e) {
            // not a bare integer — fall through to the floating-point/clamp parse
        }
        try {
            double d = Double.parseDouble(candidate);
            if (Double.isNaN(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                return null; // not a plausible HTTP status — classification falls back
            }
            return (int) d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * Index of the closing quote of the string starting at the quote at {@code i}, or -1 when
     * the string is unterminated (the scan overran the input — a malformed, typically
     * truncated, string). Never overruns, so callers must treat -1 as "not a well-formed
     * string" rather than slicing past the end.
     */
    private static int stringEnd(String s, int i) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** End index (exclusive) of the JSON value starting at {@code i}, or -1 when the value
     * is an unterminated string (malformed — the caller must not slice). */
    private static int valueEnd(String s, int i) {
        if (i >= s.length()) {
            return i;
        }
        char c = s.charAt(i);
        if (c == '"') {
            int end = stringEnd(s, i);
            return end < 0 ? -1 : end + 1;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            boolean inString = false;
            for (int j = i; j < s.length(); j++) {
                char ch = s.charAt(j);
                if (inString) {
                    if (ch == '\\') {
                        j++;
                    } else if (ch == '"') {
                        inString = false;
                    }
                } else if (ch == '"') {
                    inString = true;
                } else if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        return j + 1;
                    }
                }
            }
            return s.length();
        }
        int j = i;
        while (j < s.length() && ",}] \t\n\r".indexOf(s.charAt(j)) < 0) {
            j++;
        }
        return j;
    }

    /**
     * Recovery for malformed input: skip to the next top-level {@code,} or {@code }}, tracking
     * strings and nesting so a separator inside a string or a nested structure does not
     * truncate the member scan early (or resume mid-structure).
     */
    private static int skipToCommaOrBrace(String s, int i) {
        int depth = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                int end = stringEnd(s, i);
                if (end < 0) {
                    return s.length(); // unterminated string — nothing left to skip to
                }
                i = end + 1;
            } else if (c == '{' || c == '[') {
                depth++;
                i++;
            } else if (c == '}' || c == ']') {
                if (c == '}' && depth == 0) {
                    return i; // enclosing object boundary
                }
                depth--;
                i++;
            } else if (c == ',' && depth == 0) {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }
}

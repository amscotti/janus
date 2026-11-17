package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.DecisionCodecException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Minimal RFC 7578 parser for the TypeSafe SDK's multipart shape: a JSON
 * {@code request} part followed by file parts named {@code asset-N}. The
 * decision face reads the body raw (so zstd/gzip compression can be removed
 * before parsing); Micronaut's multipart binder is deliberately not used
 * because it would see compressed bytes.
 *
 * <p>Only the header subset the SDK emits is interpreted:
 * {@code Content-Disposition} (name/filename) and {@code Content-Type}. Binary
 * payloads are copied verbatim; the boundary must be present and the closing
 * delimiter required.
 */
final class DecisionMultipartParser {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};
    private static final int[] HEADER_END_LPS = prefixTable(HEADER_END);
    private static final int MAX_PARTS = 512;
    private static final int MAX_BOUNDARY_LENGTH = 70;

    private DecisionMultipartParser() {}

    record Part(String name, String filename, String contentType, byte[] data) {}

    static List<Part> parse(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            throw invalid("multipart body is empty");
        }
        String boundary = boundary(contentType);
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] contentDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int[] delimiterLps = prefixTable(delimiter);
        int[] contentDelimiterLps = prefixTable(contentDelimiter);

        int position = findDelimiter(body, delimiter, delimiterLps, 0, true);
        if (position < 0) {
            throw invalid("multipart boundary not found");
        }
        position += delimiter.length;
        List<Part> parts = new ArrayList<>();
        while (true) {
            if (position + 1 < body.length && body[position] == '-' && body[position + 1] == '-') {
                return parts;
            }
            if (position + 1 < body.length && body[position] == CRLF[0] && body[position + 1] == CRLF[1]) {
                position += 2;
            } else {
                throw invalid("malformed multipart delimiter");
            }
            int headersEnd = kmpIndexOf(body, HEADER_END, HEADER_END_LPS, position);
            if (headersEnd < 0) {
                throw invalid("multipart part has no header terminator");
            }
            String headers = new String(body, position, headersEnd - position, StandardCharsets.ISO_8859_1);
            int contentStart = headersEnd + HEADER_END.length;
            int next = findDelimiter(body, contentDelimiter, contentDelimiterLps, contentStart, false);
            if (next < 0) {
                throw invalid("multipart part has no closing delimiter");
            }
            if (parts.size() >= MAX_PARTS) {
                throw invalid("multipart body has too many parts");
            }
            parts.add(part(headers, Arrays.copyOfRange(body, contentStart, next)));
            position = next + contentDelimiter.length;
        }
    }

    private static Part part(String headers, byte[] data) {
        String name = null;
        String filename = null;
        String contentType = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("content-disposition".equals(key)) {
                name = parameter(value, "name");
                filename = parameter(value, "filename");
            } else if ("content-type".equals(key)) {
                contentType = value;
            }
        }
        if (name == null || name.isBlank()) {
            throw invalid("multipart part is missing its name");
        }
        return new Part(name, filename, contentType, data);
    }

    private static String boundary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw invalid("multipart Content-Type is missing its boundary");
        }
        String value = parameter(contentType, "boundary");
        if (value == null || value.isBlank()) {
            throw invalid("multipart Content-Type is missing its boundary");
        }
        if (value.length() > MAX_BOUNDARY_LENGTH) {
            throw invalid("multipart boundary exceeds 70 characters");
        }
        if (value.charAt(value.length() - 1) == ' ') {
            throw invalid("multipart boundary must not end with whitespace");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw invalid("multipart boundary contains invalid characters");
            }
        }
        return value;
    }

    /**
     * Extract an exact {@code name=value} header parameter after the initial media/disposition
     * type token, honoring RFC 9110/7578 optional whitespace around {@code =} and quoted
     * strings containing {@code ;} or escaped pairs.
     */
    private static String parameter(String header, String targetName) {
        int len = header.length();
        boolean pastFirstSemicolon = false;
        boolean inQuotes = false;
        int segmentStart = 0;
        for (int i = 0; i <= len; i++) {
            char c = i < len ? header.charAt(i) : ';';
            if (inQuotes) {
                if (c == '\\' && i + 1 < len) {
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                }
                continue;
            }
            if (c == '"' && i < len) {
                inQuotes = true;
                continue;
            }
            if (c == ';') {
                if (!pastFirstSemicolon) {
                    pastFirstSemicolon = true;
                } else {
                    String matched = matchParameter(header, segmentStart, i, targetName);
                    if (matched != null) {
                        return matched;
                    }
                }
                segmentStart = i + 1;
            }
        }
        return null;
    }

    private static String matchParameter(String header, int start, int end, String targetName) {
        int eq = header.indexOf('=', start);
        if (eq < 0 || eq >= end) {
            return null;
        }
        String paramName = header.substring(start, eq).trim();
        if (!paramName.equalsIgnoreCase(targetName)) {
            return null;
        }
        int vStart = eq + 1;
        while (vStart < end && isOws(header.charAt(vStart))) {
            vStart++;
        }
        int vEnd = end;
        while (vEnd > vStart && isOws(header.charAt(vEnd - 1))) {
            vEnd--;
        }
        if (vStart >= vEnd) {
            return null;
        }
        if (header.charAt(vStart) == '"') {
            if (vEnd - vStart < 2 || header.charAt(vEnd - 1) != '"') {
                return null;
            }
            StringBuilder unescaped = new StringBuilder(vEnd - vStart - 2);
            for (int i = vStart + 1; i < vEnd - 1; i++) {
                char c = header.charAt(i);
                if (c == '\\' && i + 1 < vEnd - 1) {
                    unescaped.append(header.charAt(++i));
                } else {
                    unescaped.append(c);
                }
            }
            return unescaped.toString();
        }
        return header.substring(vStart, vEnd);
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }

    private static int findDelimiter(byte[] haystack, byte[] pattern, int[] lps, int from, boolean requireLineStart) {
        int j = 0;
        for (int i = Math.max(0, from); i < haystack.length; i++) {
            while (j > 0 && haystack[i] != pattern[j]) {
                j = lps[j - 1];
            }
            if (haystack[i] == pattern[j]) {
                j++;
                if (j == pattern.length) {
                    int matchStart = i - pattern.length + 1;
                    int after = i + 1;
                    boolean validLead = !requireLineStart
                            || matchStart == 0
                            || (matchStart >= 2
                                    && haystack[matchStart - 2] == '\r'
                                    && haystack[matchStart - 1] == '\n');
                    if (validLead && isDelimiterSuffix(haystack, after)) {
                        return matchStart;
                    }
                    j = lps[j - 1];
                }
            }
        }
        return -1;
    }

    private static boolean isDelimiterSuffix(byte[] body, int after) {
        if (after + 1 >= body.length) {
            return false;
        }
        return (body[after] == '\r' && body[after + 1] == '\n') || (body[after] == '-' && body[after + 1] == '-');
    }

    private static int kmpIndexOf(byte[] haystack, byte[] pattern, int[] lps, int from) {
        int j = 0;
        for (int i = Math.max(0, from); i < haystack.length; i++) {
            while (j > 0 && haystack[i] != pattern[j]) {
                j = lps[j - 1];
            }
            if (haystack[i] == pattern[j]) {
                j++;
                if (j == pattern.length) {
                    return i - pattern.length + 1;
                }
            }
        }
        return -1;
    }

    private static int[] prefixTable(byte[] pattern) {
        int[] lps = new int[pattern.length];
        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j > 0 && pattern[i] != pattern[j]) {
                j = lps[j - 1];
            }
            if (pattern[i] == pattern[j]) {
                lps[i] = ++j;
            }
        }
        return lps;
    }

    private static DecisionCodecException invalid(String message) {
        return new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, message);
    }
}

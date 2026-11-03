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
    private static final int MAX_PARTS = 512;

    private DecisionMultipartParser() {}

    record Part(String name, String filename, String contentType, byte[] data) {}

    static List<Part> parse(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            throw invalid("multipart body is empty");
        }
        String boundary = boundary(contentType);
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] contentDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        int position = indexOf(body, delimiter, 0);
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
            int headersEnd = indexOf(body, HEADER_END, position);
            if (headersEnd < 0) {
                throw invalid("multipart part has no header terminator");
            }
            String headers = new String(body, position, headersEnd - position, StandardCharsets.ISO_8859_1);
            int contentStart = headersEnd + HEADER_END.length;
            int next = indexOf(body, contentDelimiter, contentStart);
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
        for (String token : contentType.split(";")) {
            String trimmed = token.trim();
            if (trimmed.regionMatches(true, 0, "boundary=", 0, "boundary=".length())) {
                String value = trimmed.substring("boundary=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        throw invalid("multipart Content-Type is missing its boundary");
    }

    /** Extract {@code name=value} from a Content-Disposition header (quoted or token). */
    private static String parameter(String header, String name) {
        String needle = name + "=";
        int index = header.toLowerCase(Locale.ROOT).indexOf(needle);
        if (index < 0) {
            return null;
        }
        int valueStart = index + needle.length();
        if (valueStart >= header.length()) {
            return null;
        }
        if (header.charAt(valueStart) == '"') {
            int end = header.indexOf('"', valueStart + 1);
            return end < 0 ? null : header.substring(valueStart + 1, end);
        }
        int end = header.indexOf(';', valueStart);
        String value = end < 0 ? header.substring(valueStart) : header.substring(valueStart, end);
        return value.trim();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        int last = haystack.length - needle.length;
        outer:
        for (int i = Math.max(0, from); i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static DecisionCodecException invalid(String message) {
        return new DecisionCodecException(DecisionCodecException.TYPE_INVALID_REQUEST, message);
    }
}

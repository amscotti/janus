package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.janus.core.codec.DecisionCodecException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecisionMultipartParserTest {

    private static final String BOUNDARY = "janus-test-boundary";

    @Test
    void parsesRequestAndBinaryFileParts() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

        List<DecisionMultipartParser.Part> parts = DecisionMultipartParser.parse(
                multipart(BOUNDARY, "{\"model\":\"jev-latest\"}", "asset-0", png),
                "multipart/form-data; boundary=" + BOUNDARY);

        assertEquals(2, parts.size());
        assertEquals("request", parts.get(0).name());
        assertNull(parts.get(0).filename());
        assertEquals("{\"model\":\"jev-latest\"}", new String(parts.get(0).data(), StandardCharsets.UTF_8));
        assertEquals("asset-0", parts.get(1).name());
        assertEquals("asset-0", parts.get(1).filename());
        assertEquals("image/png", parts.get(1).contentType());
        assertArrayEquals(png, parts.get(1).data());
    }

    @Test
    void acceptsQuotedBoundaryAndPreamble() {
        byte[] body = multipart(BOUNDARY, "{}", "asset-0", new byte[] {1, 2, 3});
        byte[] withPreamble = new byte[body.length + 10];
        System.arraycopy("preamble\r\n".getBytes(StandardCharsets.ISO_8859_1), 0, withPreamble, 0, 10);
        System.arraycopy(body, 0, withPreamble, 10, body.length);

        List<DecisionMultipartParser.Part> parts =
                DecisionMultipartParser.parse(withPreamble, "multipart/form-data; boundary=\"" + BOUNDARY + "\"");

        assertEquals(2, parts.size());
        assertEquals("asset-0", parts.get(1).name());
        assertArrayEquals(new byte[] {1, 2, 3}, parts.get(1).data());
    }

    @Test
    void rejectsMissingHeaderTerminatorClosingDelimiterAndTooManyParts() {
        byte[] missingHeadersEnd = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"request\"\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(missingHeadersEnd, "multipart/form-data; boundary=" + BOUNDARY));

        byte[] noClosing = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n{}")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(noClosing, "multipart/form-data; boundary=" + BOUNDARY));

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 513; i++) {
            many.append("--")
                    .append(BOUNDARY)
                    .append("\r\n")
                    .append("Content-Disposition: form-data; name=\"p")
                    .append(i)
                    .append("\"\r\n\r\n\r\n");
        }
        many.append("--").append(BOUNDARY).append("--\r\n");
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(
                        many.toString().getBytes(StandardCharsets.ISO_8859_1),
                        "multipart/form-data; boundary=" + BOUNDARY));
    }

    @Test
    void rejectsMissingBoundaryTruncatedPartsAndUnnamedParts() {
        byte[] body = multipart(BOUNDARY, "{}", "asset-0", new byte[] {1});

        assertThrows(DecisionCodecException.class, () -> DecisionMultipartParser.parse(body, "multipart/form-data"));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(body, "multipart/form-data; boundary=\"\""));
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(
                        "not-a-multipart-body".getBytes(StandardCharsets.UTF_8),
                        "multipart/form-data; boundary=" + BOUNDARY));

        byte[] noName = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(noName, "multipart/form-data; boundary=" + BOUNDARY));
    }

    private static byte[] multipart(String boundary, String request, String filename, byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"request\"\r\n"
                            + "Content-Type: application/json\r\n\r\n"
                            + request + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"" + filename + "\"; filename=\"" + filename
                            + "\"\r\n"
                            + "Content-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

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

    @Test
    void filenameBeforeNameDoesNotCollideAndOwsAroundEqualsIsAccepted() {
        byte[] img = new byte[] {1, 2, 3, 4};
        String payload = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name = \"request\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"model\":\"jev-latest\"}\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; filename=\"request\"; name=\"asset-0\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        byte[] head = payload.getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[head.length + img.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(img, 0, body, head.length, img.length);
        System.arraycopy(tail, 0, body, head.length + img.length, tail.length);

        List<DecisionMultipartParser.Part> parts =
                DecisionMultipartParser.parse(body, "multipart/form-data; boundary = \"" + BOUNDARY + "\"");

        assertEquals(2, parts.size());
        assertEquals("request", parts.get(0).name());
        assertEquals("{\"model\":\"jev-latest\"}", new String(parts.get(0).data(), StandardCharsets.UTF_8));
        assertEquals("asset-0", parts.get(1).name());
        assertEquals("request", parts.get(1).filename());
        assertArrayEquals(img, parts.get(1).data());
    }

    @Test
    void filenameBeforeAssetNameWorksRegardlessOfFilenameValue() {
        byte[] body = ("--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; filename=\"x.png\"; name=\"asset-0\"\r\n"
                        + "Content-Type: image/png\r\n\r\n"
                        + "abc\r\n"
                        + "--" + BOUNDARY + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);

        List<DecisionMultipartParser.Part> parts =
                DecisionMultipartParser.parse(body, "multipart/form-data; boundary=" + BOUNDARY);

        assertEquals(1, parts.size());
        assertEquals("asset-0", parts.get(0).name());
        assertEquals("x.png", parts.get(0).filename());
    }

    @Test
    void acceptsQuotedSemicolonInBoundaryAndBinaryNearDelimiterInsidePartData() {
        String semicolonBoundary = "janus;boundary=1";
        byte[] binaryPayload =
                ("\r\n--" + semicolonBoundary + "Xbinary-continues").getBytes(StandardCharsets.ISO_8859_1);
        byte[] head = ("--" + semicolonBoundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"asset-0\"; filename=\"bin.dat\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + semicolonBoundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[head.length + binaryPayload.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(binaryPayload, 0, body, head.length, binaryPayload.length);
        System.arraycopy(tail, 0, body, head.length + binaryPayload.length, tail.length);

        List<DecisionMultipartParser.Part> parts =
                DecisionMultipartParser.parse(body, "multipart/form-data; boundary=\"" + semicolonBoundary + "\"");

        assertEquals(1, parts.size());
        assertEquals("asset-0", parts.get(0).name());
        assertArrayEquals(binaryPayload, parts.get(0).data());
    }

    @Test
    void enforcesRfc2046SeventyCharBoundaryLimitAndLinearTimeScan() {
        String boundary70 = "b".repeat(70);
        byte[] valid70 = ("--" + boundary70 + "\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n{}\r\n--"
                        + boundary70 + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(
                1,
                DecisionMultipartParser.parse(valid70, "multipart/form-data; boundary=" + boundary70)
                        .size());

        String boundary71 = "b".repeat(71);
        byte[] body71 = ("--" + boundary71 + "\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n{}\r\n--"
                        + boundary71 + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(
                DecisionCodecException.class,
                () -> DecisionMultipartParser.parse(body71, "multipart/form-data; boundary=" + boundary71));

        // Linear-time KMP check: 256 KiB of near-match repeated boundary prefixes must parse quickly.
        String longBoundary = "a".repeat(69) + "b";
        String nearMatch = ("\r\n--" + "a".repeat(69) + "c").repeat(3_500);
        byte[] body = ("--" + longBoundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\n\r\n" + nearMatch
                        + "\r\n--" + longBoundary + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        List<DecisionMultipartParser.Part> parts =
                DecisionMultipartParser.parse(body, "multipart/form-data; boundary=" + longBoundary);
        assertEquals(1, parts.size());
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

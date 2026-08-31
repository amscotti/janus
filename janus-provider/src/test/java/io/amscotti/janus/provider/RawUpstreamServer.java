package io.amscotti.janus.provider;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: a loopback upstream that can send the HTTP response head and flush it
 * <b>immediately</b>, then write the body later. The JDK {@code
 * com.sun.net.httpserver.HttpServer} buffers response headers until the first body write,
 * so it cannot exercise the adapters' header-arrival-only request-timeout semantics (the
 * {@code com.sun.net.httpserver} would trip the request timeout on
 * any delayed-body test regardless of the client's actual behavior). Accepts connections
 * in a loop (one daemon thread per connection), so it can also pin the keep-alive
 * connection-reuse contract with a server that keeps the connection open across several
 * requests.
 */
final class RawUpstreamServer implements AutoCloseable {

    /** Writes the full response (head + body) to the accepted socket's output stream. */
    @FunctionalInterface
    interface Upstream {
        void serve(java.io.OutputStream out) throws Exception;
    }

    /** Socket-level handler: owns the whole connection (request-head reads, responses,
     * keep-alive loop). Runs on a fresh daemon thread per accepted connection. */
    @FunctionalInterface
    interface ConnectionUpstream {
        void serve(Socket socket) throws Exception;
    }

    private final ServerSocket serverSocket;
    private final Thread thread;
    private final List<Thread> connectionThreads = new CopyOnWriteArrayList<>();

    private RawUpstreamServer(ServerSocket serverSocket, ConnectionUpstream upstream) {
        this.serverSocket = serverSocket;
        this.thread = new Thread(
                () -> {
                    try {
                        while (true) {
                            Socket socket = serverSocket.accept();
                            Thread connection = new Thread(
                                    () -> {
                                        try (Socket s = socket) {
                                            s.setSoTimeout(10_000);
                                            upstream.serve(s);
                                        } catch (Throwable ignored) {
                                            // the client-side assertions are the test; a broken server
                                            // connection surfaces as a client error or timeout, never a hang
                                        }
                                    },
                                    "raw-upstream-conn");
                            connection.setDaemon(true);
                            connectionThreads.add(connection);
                            connection.start();
                        }
                    } catch (IOException ignored) {
                        // close closed the server socket — the serve loop is over
                    }
                },
                "raw-upstream");
        thread.setDaemon(true);
        thread.start();
    }

    private RawUpstreamServer(ServerSocket serverSocket, Upstream upstream) {
        this(serverSocket, (ConnectionUpstream) socket -> {
            readRequestHead(socket);
            upstream.serve(socket.getOutputStream());
        });
    }

    /**
     * Serves one request: 200 head flushed immediately, {@code body} written after
     * {@code bodyDelayMs} — a server that is healthy and progressing (sends headers) but
     * takes longer than the request timeout to deliver the body.
     */
    static RawUpstreamServer headersThenDelayedBody(String contentType, byte[] body, long bodyDelayMs)
            throws IOException {
        return start(200, contentType, body, bodyDelayMs);
    }

    /**
     * Serves one request: {@code status} head flushed immediately, {@code body} written
     * after {@code bodyDelayMs} — a server that is healthy and progressing (sends the
     * head) but takes longer than the body-read deadline to deliver the body.
     */
    static RawUpstreamServer start(int status, String contentType, byte[] body, long bodyDelayMs) throws IOException {
        return start(out -> {
            out.write(head(status, contentType, body.length).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(bodyDelayMs);
            out.write(body);
            out.flush();
        });
    }

    /** Serves one request with a socket-level handler (the delayed-body shapes). */
    static RawUpstreamServer start(Upstream upstream) throws IOException {
        return new RawUpstreamServer(new ServerSocket(0), upstream);
    }

    /** Socket-level factory — the handler controls the whole connection (keep-alive loops,
     * request-head reads via {@link #readRequestHead(Socket)}). */
    static RawUpstreamServer startOnSocket(ConnectionUpstream upstream) throws IOException {
        return new RawUpstreamServer(new ServerSocket(0), upstream);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    /** Reads one HTTP request head (up to and including the blank line) from the socket,
     * then drains the request body so a keep-alive loop can read the next head cleanly. */
    static void readRequestHead(Socket socket) throws IOException {
        byte[] buf = new byte[8192];
        int total = 0;
        while (total < buf.length) {
            int n = socket.getInputStream().read(buf, total, buf.length - total);
            if (n < 0) {
                return;
            }
            total += n;
            if (new String(buf, 0, total, StandardCharsets.UTF_8).contains("\r\n\r\n")) {
                break;
            }
        }
        String head = new String(buf, 0, total, StandardCharsets.UTF_8);
        int bodyStart = head.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            return;
        }
        int length = contentLength(head.substring(0, bodyStart));
        int consumed = total - bodyStart - 4; // bytes of the body already read
        if (length > consumed) {
            byte[] rest = new byte[length - consumed];
            int have = 0;
            while (have < rest.length) {
                int n = socket.getInputStream().read(rest, have, rest.length - have);
                if (n < 0) {
                    return;
                }
                have += n;
            }
        }
    }

    private static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).strip());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String head(int status, String contentType, int contentLength) {
        String reason = status == 200 ? "OK" : status == 503 ? "Service Unavailable" : "Error";
        return "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "\r\n";
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best-effort
        }
        thread.interrupt();
        for (Thread connection : connectionThreads) {
            connection.interrupt();
        }
    }
}

package io.andreygrigoriev.yfin.live;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal RFC 6455 server on a plain {@link ServerSocket}, enough for the JDK WebSocket client:
 * handshake, masked client text frames (with continuations), close and ping, unmasked server text
 * frames (optionally fragmented).
 */
final class TestWebSocketServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final BlockingQueue<Connection> accepted = new LinkedBlockingQueue<>();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();

    TestWebSocketServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("test-ws-accept").start(this::acceptLoop);
    }

    URI uri() {
        return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/?version=2");
    }

    /** The next connection that completed its handshake. */
    Connection awaitConnection(Duration timeout) throws InterruptedException {
        Connection c = accepted.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (c == null) {
            throw new AssertionError("no connection within " + timeout);
        }
        return c;
    }

    int connectionCount() {
        return connections.size();
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Connection c : connections) {
            c.drop();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().name("test-ws-conn").start(() -> handle(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            Map<String, String> headers = readHandshake(in);
            String accept = Base64.getEncoder().encodeToString(sha1(headers.get("sec-websocket-key") + GUID));
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Connection c = new Connection(socket, headers);
            connections.add(c);
            accepted.add(c);
            c.readLoop(in);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static Map<String, String> readHandshake(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        while (!buf.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("handshake");
            }
            buf.write(b);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = buf.toString(StandardCharsets.US_ASCII).split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(
                        lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static byte[] sha1(String s) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One accepted client connection. */
    static final class Connection {

        private final Socket socket;
        private final Map<String, String> headers;
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final Object writeLock = new Object();

        Connection(Socket socket, Map<String, String> headers) {
            this.socket = socket;
            this.headers = headers;
        }

        /** Handshake request header, lower-case name. */
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** Next text message from the client. */
        String receive(Duration timeout) throws InterruptedException {
            String s = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (s == null) {
                throw new AssertionError("no message within " + timeout);
            }
            return s;
        }

        void sendText(String text) throws IOException {
            writeFrame(0x81, text.getBytes(StandardCharsets.UTF_8));
        }

        /** Sends {@code text} as {@code parts} frames: text + continuations. */
        void sendFragmented(String text, int parts) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
            int size = (bytes.length + parts - 1) / parts;
            for (int i = 0, off = 0; off < bytes.length; i++, off += size) {
                int len = Math.min(size, bytes.length - off);
                byte[] chunk = new byte[len];
                System.arraycopy(bytes, off, chunk, 0, len);
                boolean fin = off + len >= bytes.length;
                writeFrame((fin ? 0x80 : 0) | (i == 0 ? 0x1 : 0x0), chunk);
            }
        }

        /** Closing handshake from the server side. */
        void sendClose(int code) throws IOException {
            writeFrame(0x88, new byte[] {(byte) (code >> 8), (byte) code});
        }

        /** Drops the TCP connection without a closing handshake. */
        void drop() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }

        private void writeFrame(int first, byte[] payload) throws IOException {
            synchronized (writeLock) {
                OutputStream out = socket.getOutputStream();
                out.write(first);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length < 65536) {
                    out.write(126);
                    out.write(payload.length >> 8);
                    out.write(payload.length);
                } else {
                    out.write(127);
                    for (int i = 7; i >= 0; i--) {
                        out.write((int) ((long) payload.length >> (8 * i)));
                    }
                }
                out.write(payload);
                out.flush();
            }
        }

        private void readLoop(InputStream in) throws IOException {
            var message = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 < 0 || b1 < 0) {
                    drop();
                    return;
                }
                int opcode = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = (in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | in.read();
                    }
                }
                byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
                byte[] payload = in.readNBytes((int) len);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                switch (opcode) {
                    case 0x1, 0x0 -> {
                        message.write(payload);
                        if (fin) {
                            received.add(message.toString(StandardCharsets.UTF_8));
                            message.reset();
                        }
                    }
                    case 0x8 -> {
                        try {
                            writeFrame(0x88, payload);
                        } catch (IOException ignored) {
                            // client went away
                        }
                        drop();
                        return;
                    }
                    case 0x9 -> writeFrame(0x8A, payload);
                    default -> {
                        // pong, binary: ignored
                    }
                }
            }
        }
    }
}

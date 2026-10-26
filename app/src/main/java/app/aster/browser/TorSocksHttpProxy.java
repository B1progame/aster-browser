package app.aster.browser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.IDN;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Local HTTP proxy that carries WebView HTTP and CONNECT traffic over Tor SOCKS5. */
public final class TorSocksHttpProxy implements AutoCloseable {
    private static final int SOCKET_TIMEOUT_MS = 120_000;
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final int socksPort;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicLong clientToTorBytes = new AtomicLong();
    private final AtomicLong torToClientBytes = new AtomicLong();
    private volatile String lastRequestKind = "none";
    private volatile String lastStage = "waiting for WebView";
    private volatile String lastFailure = "none";
    private volatile String lastFailureStage = "none";
    private final ExecutorService workers = Executors.newCachedThreadPool(new DaemonThreadFactory());
    private volatile boolean running;
    private volatile ServerSocket listener;

    public TorSocksHttpProxy(int socksPort) {
        if (socksPort < 1 || socksPort > 65535) throw new IllegalArgumentException("Invalid Tor SOCKS port");
        this.socksPort = socksPort;
    }

    public synchronized int start() throws IOException {
        if (running) return listener.getLocalPort();
        ServerSocket server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        server.setReuseAddress(true);
        listener = server;
        running = true;
        Thread acceptThread = new DaemonThreadFactory().newThread(() -> acceptLoop(server));
        acceptThread.start();
        return server.getLocalPort();
    }

    public int port() {
        ServerSocket server = listener;
        if (!running || server == null) throw new IllegalStateException("Tor HTTP proxy is not running");
        return server.getLocalPort();
    }

    /** Number of requests accepted; destination hosts are never retained. */
    public int getRequestCount() { return requestCount.get(); }

    /** Sanitized runtime diagnostics: never includes destination host, path, or page content. */
    public String diagnosticSummary() {
        String transfer = " · tunnel bytes " + clientToTorBytes.get() + "↑/" + torToClientBytes.get() + "↓";
        if ("none".equals(lastFailure)) return "last " + lastRequestKind + " · " + lastStage + transfer;
        return "last " + lastRequestKind + " · failed at " + lastFailureStage + " · " + lastFailure + transfer;
    }

    private void acceptLoop(ServerSocket server) {
        while (running) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                client.setTcpNoDelay(true);
                clients.add(client);
                workers.execute(() -> handle(client));
            } catch (SocketException closed) {
                if (running) closeQuietly(server);
                return;
            } catch (IOException | RuntimeException error) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) return;
            requestCount.incrementAndGet();
            String[] parts = requestLine.split(" ", 3);
            if (parts.length != 3) throw new IOException("Invalid HTTP request line");
            lastFailure = "none";
            lastFailureStage = "none";
            lastRequestKind = parts[0].equalsIgnoreCase("CONNECT") ? "CONNECT" : "HTTP";
            lastStage = "reading headers";
            List<String> headers = readHeaders(input);
            if ("CONNECT".equalsIgnoreCase(parts[0])) {
                Target target = parseAuthority(parts[1], 443);
                lastStage = "opening Tor SOCKS stream";
                try (Socket upstream = connectThroughTor(target)) {
                    lastStage = "Tor SOCKS stream established";
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    lastStage = "relaying encrypted tunnel";
                relayDuplex(client, input, upstream, clientToTorBytes, torToClientBytes);
                }
            } else {
                Target target = parseHttpTarget(parts[1], headers);
                lastStage = "opening Tor SOCKS stream";
                try (Socket upstream = connectThroughTor(target)) {
                    lastStage = "sending HTTP request through Tor";
                    OutputStream upstreamOutput = upstream.getOutputStream();
                    upstreamOutput.write((parts[0] + " " + target.path + " " + parts[2] + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    boolean hasConnectionHeader = false;
                    long contentLength = 0;
                    boolean chunked = false;
                    for (String header : headers) {
                        int colon = header.indexOf(':');
                        String name = colon < 0 ? "" : header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        if (name.equals("proxy-connection") || name.equals("proxy-authorization")) continue;
                        if (name.equals("connection")) {
                            upstreamOutput.write("Connection: close\r\n".getBytes(StandardCharsets.US_ASCII));
                            hasConnectionHeader = true;
                            continue;
                        }
                        if (name.equals("content-length")) {
                            try { contentLength = Math.max(0, Long.parseLong(header.substring(colon + 1).trim())); }
                            catch (NumberFormatException invalid) { throw new IOException("Invalid Content-Length", invalid); }
                        }
                        if (name.equals("transfer-encoding") && header.toLowerCase(Locale.ROOT).contains("chunked")) chunked = true;
                        upstreamOutput.write((header + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                    if (!hasConnectionHeader) upstreamOutput.write("Connection: close\r\n".getBytes(StandardCharsets.US_ASCII));
                    upstreamOutput.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    if (chunked) relayChunkedBody(input, upstreamOutput);
                    else if (contentLength > 0) copyExactly(input, upstreamOutput, contentLength);
                    upstreamOutput.flush();
                    upstream.shutdownOutput();
                    inputStream(upstream).transferTo(output);
                    output.flush();
                }
            }
        } catch (IOException | RuntimeException error) {
            lastFailureStage = lastStage;
            lastFailure = error.getClass().getSimpleName();
            lastStage = "request failed";
            if (!client.isClosed()) writeGatewayError(client);
        } finally {
            clients.remove(client);
            closeQuietly(client);
        }
    }

    private Socket connectThroughTor(Target target) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort), 10_000);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        try {
            lastStage = "negotiating Tor SOCKS";
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            output.write(new byte[] {5, 1, 0});
            output.flush();
            byte[] greeting = readExactly(input, 2);
            if (greeting[0] != 5 || greeting[1] != 0) throw new IOException("Tor SOCKS5 rejected no-auth mode");
            lastStage = "requesting destination from Tor";
            String hostName = target.host;
            if (hostName.startsWith("[") && hostName.endsWith("]")) {
                hostName = hostName.substring(1, hostName.length() - 1);
            }
            byte[] hostBytes;
            int addressType;
            if (hostName.indexOf(':') >= 0) {
                hostBytes = InetAddress.getByName(hostName).getAddress();
                if (hostBytes.length != 16) throw new IOException("Invalid IPv6 destination");
                addressType = 4;
            } else if (isIpv4Literal(hostName)) {
                hostBytes = InetAddress.getByName(hostName).getAddress();
                if (hostBytes.length != 4) throw new IOException("Invalid IPv4 destination");
                addressType = 1;
            } else {
                hostBytes = IDN.toASCII(hostName, IDN.USE_STD3_ASCII_RULES).getBytes(StandardCharsets.US_ASCII);
                if (hostBytes.length == 0 || hostBytes.length > 255) throw new IOException("Destination host is too long");
                addressType = 3;
            }
            output.write(new byte[] {5, 1, 0, (byte) addressType});
            if (addressType == 3) output.write(hostBytes.length);
            output.write(hostBytes);
            output.write(new byte[] {(byte) (target.port >>> 8), (byte) target.port});
            output.flush();
            byte[] reply = readExactly(input, 4);
            if (reply[0] != 5 || reply[1] != 0) throw new IOException("Tor SOCKS5 could not open destination stream");
            int addressLength;
            switch (reply[3] & 0xff) {
                case 1: addressLength = 4; break;
                case 4: addressLength = 16; break;
                case 3: addressLength = input.read(); break;
                default: throw new IOException("Tor SOCKS5 returned an invalid address type");
            }
            if (addressLength < 0) throw new IOException("Tor SOCKS5 reply was truncated");
            readExactly(input, addressLength + 2);
            lastStage = "Tor destination stream ready";
            return socket;
        } catch (IOException | RuntimeException error) {
            closeQuietly(socket);
            throw error;
        }
    }

    private static Target parseHttpTarget(String requestTarget, List<String> headers) throws IOException {
        try {
            URI uri = URI.create(requestTarget);
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            if (host == null) {
                String authority = headerValue(headers, "host");
                if (authority == null) throw new IOException("HTTP request has no destination host");
                Target parsed = parseAuthority(authority, 80);
                return new Target(parsed.host, parsed.port, requestTarget);
            }
            if (uri.getScheme() != null && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Only plain HTTP requests may use absolute-form here");
            }
            return new Target(host, port > 0 ? port : 80, path);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid HTTP destination", error);
        }
    }

    private static Target parseAuthority(String authority, int defaultPort) throws IOException {
        String host;
        int port = defaultPort;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) throw new IOException("Invalid IPv6 proxy destination");
            host = authority.substring(1, end);
            if (authority.length() > end + 1) {
                if (authority.charAt(end + 1) != ':') throw new IOException("Invalid proxy destination port");
                port = parsePort(authority.substring(end + 2));
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && authority.indexOf(':') == colon) {
                host = authority.substring(0, colon);
                port = parsePort(authority.substring(colon + 1));
            } else host = authority;
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new IOException("Invalid proxy destination");
        return new Target(host, port, "");
    }

    private static int parsePort(String value) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IOException("Invalid proxy destination port", error); }
    }

    private static boolean isIpv4Literal(String host) {
        if (!host.matches("[0-9.]+")) return false;
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            try { if (Integer.parseInt(octet) > 255) return false; }
            catch (NumberFormatException invalid) { return false; }
        }
        return true;
    }

    private static String headerValue(List<String> headers, String name) {
        for (String line : headers) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    private static List<String> readHeaders(InputStream input) throws IOException {
        List<String> headers = new ArrayList<>();
        int total = 0;
        while (true) {
            String line = readLine(input);
            if (line == null) throw new IOException("HTTP headers were truncated");
            total += line.length();
            if (total > MAX_HEADER_BYTES) throw new IOException("HTTP headers are too large");
            if (line.isEmpty()) return headers;
            headers.add(line);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= MAX_LINE_BYTES) {
            int value = input.read();
            if (value < 0) return line.size() == 0 ? null : line.toString("US-ASCII");
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            line.write(value);
            previous = value;
        }
        throw new IOException("HTTP line is too long");
    }

    private static void relayChunkedBody(InputStream input, OutputStream output) throws IOException {
        while (true) {
            String sizeLine = readLine(input);
            if (sizeLine == null) throw new IOException("Chunked request body was truncated");
            output.write((sizeLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String sizeText = sizeLine.split(";", 2)[0].trim();
            long size;
            try { size = Long.parseLong(sizeText, 16); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid HTTP chunk size", invalid); }
            if (size == 0) {
                while (true) {
                    String trailer = readLine(input);
                    if (trailer == null) throw new IOException("Chunked request trailers were truncated");
                    output.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    if (trailer.isEmpty()) return;
                }
            }
            copyExactly(input, output, size);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid HTTP chunk terminator");
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("HTTP request body was truncated");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("Tor SOCKS5 response was truncated");
            offset += count;
        }
        return result;
    }

    private static InputStream inputStream(Socket socket) throws IOException { return socket.getInputStream(); }

    private static void relayDuplex(Socket client, InputStream clientInput, Socket upstream,
                                    AtomicLong clientToTorBytes, AtomicLong torToClientBytes) throws IOException {
        CountDownLatch done = new CountDownLatch(2);
        AtomicBoolean abort = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<IOException> relayFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread upstreamToClient = new DaemonThreadFactory().newThread(() -> {
            try {
                copyAndCount(upstream.getInputStream(), client.getOutputStream(), torToClientBytes);
                client.getOutputStream().flush();
                if (!client.isClosed() && !client.isOutputShutdown()) client.shutdownOutput();
            } catch (IOException error) {
                if (!abort.get()) {
                    relayFailure.compareAndSet(null, error);
                    abort.set(true);
                    closeQuietly(client);
                    closeQuietly(upstream);
                }
            } finally {
                done.countDown();
            }
        });
        upstreamToClient.start();
        // The HTTP parser uses a BufferedInputStream. It may already contain the start of
        // the TLS ClientHello sent in the same packet as CONNECT; keep reading through it
        // or those bytes disappear when switching to the raw socket stream.
        try {
            copyAndCount(clientInput, upstream.getOutputStream(), clientToTorBytes);
            upstream.getOutputStream().flush();
            if (!upstream.isClosed() && !upstream.isOutputShutdown()) upstream.shutdownOutput();
        } catch (IOException error) {
            if (!abort.get()) {
                relayFailure.compareAndSet(null, error);
                abort.set(true);
                closeQuietly(client);
                closeQuietly(upstream);
            }
        } finally {
            done.countDown();
        }
        try {
            if (!done.await(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                closeQuietly(client);
                closeQuietly(upstream);
                throw new IOException("Tor tunnel relay timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(client);
            closeQuietly(upstream);
            throw new IOException("Tor tunnel relay interrupted", interrupted);
        }
        IOException failure = relayFailure.get();
        if (failure != null) throw failure;
    }

    private static void copyAndCount(InputStream input, OutputStream output, AtomicLong byteCount) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            byteCount.addAndGet(count);
        }
    }

    private static void writeGatewayError(Socket client) {
        try {
            OutputStream output = client.getOutputStream();
            byte[] body = "Tor proxy connection failed".getBytes(StandardCharsets.US_ASCII);
            output.write(("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Type: text/plain\r\nContent-Length: "
                    + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        running = false;
        closeQuietly(listener);
        for (Socket socket : clients) closeQuietly(socket);
        clients.clear();
        workers.shutdownNow();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    private static final class Target {
        final String host;
        final int port;
        final String path;
        Target(String host, int port, String path) { this.host = host; this.port = port; this.path = path; }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private int nextId;
        @Override public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "aster-tor-http-proxy-" + ++nextId);
            thread.setDaemon(true);
            return thread;
        }
    }
}

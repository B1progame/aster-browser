package app.aster.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class TorSocksHttpProxyTest {
    @Test
    public void connectsHttpsBySocksDomainAndRelaysBytes() throws Exception {
        try (FakeSocks socks = new FakeSocks(); TorSocksHttpProxy proxy = new TorSocksHttpProxy(socks.port())) {
            proxy.start();
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(5000);
                OutputStream output = client.getOutputStream();
                InputStream input = client.getInputStream();
                // TLS clients may send ClientHello immediately after CONNECT headers in
                // the same packet. The proxy's buffered header reader must preserve these
                // already-buffered tunnel bytes when it switches to duplex relay.
                output.write(("CONNECT check.torproject.org:443 HTTP/1.1\r\n"
                        + "Host: check.torproject.org:443\r\n\r\nping")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                // The request side can finish before the remote side sends its response.
                // The proxy must half-close upstream output and keep receiving the reply.
                client.shutdownOutput();
                assertTrue(readHeaders(input).startsWith("HTTP/1.1 200 Connection Established"));
                assertEquals("check.torproject.org:443", socks.target.poll(5, TimeUnit.SECONDS));
                byte[] reply = new byte[4];
                readFully(input, reply);
                assertEquals("pong", new String(reply, StandardCharsets.US_ASCII));
                assertEquals("last CONNECT · relaying encrypted tunnel · tunnel bytes 4↑/4↓",
                        proxy.diagnosticSummary());
            }
        }
    }

    @Test
    public void forwardsHttpOriginFormThroughSocksAndRemovesProxyHeaders() throws Exception {
        try (FakeSocks socks = new FakeSocks(); TorSocksHttpProxy proxy = new TorSocksHttpProxy(socks.port())) {
            proxy.start();
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(5000);
                OutputStream output = client.getOutputStream();
                InputStream input = client.getInputStream();
                output.write(("GET http://example.com/path?q=one HTTP/1.1\r\n"
                        + "Host: example.com\r\nProxy-Connection: keep-alive\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                assertEquals("example.com:80", socks.target.poll(5, TimeUnit.SECONDS));
                String request = socks.requestHeaders.poll(5, TimeUnit.SECONDS);
                assertTrue(request != null);
                assertTrue(request.startsWith("GET /path?q=one HTTP/1.1\r\n"));
                assertTrue(!request.toLowerCase().contains("proxy-connection:"));
                socks.tunnelOutput.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                        + "Connection: close\r\n\r\nok")
                        .getBytes(StandardCharsets.US_ASCII));
                socks.tunnelOutput.flush();
                socks.responseReady.countDown();
                assertTrue(readHeaders(input).startsWith("HTTP/1.1 200 OK"));
                byte[] body = new byte[2];
                readFully(input, body);
                assertEquals("ok", new String(body, StandardCharsets.US_ASCII));
            }
        }
    }

    @Test
    public void reportsSanitizedFailureStageWhenTorSocksListenerIsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = reserved.getLocalPort();
        }
        try (TorSocksHttpProxy proxy = new TorSocksHttpProxy(closedPort)) {
            proxy.start();
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(5000);
                client.getOutputStream().write(("CONNECT private.example:443 HTTP/1.1\r\n"
                        + "Host: private.example:443\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 502 Bad Gateway"));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            String diagnostic;
            do {
                diagnostic = proxy.diagnosticSummary();
                if (diagnostic.contains("failed at opening Tor SOCKS stream")) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertTrue(diagnostic, diagnostic.contains("failed at opening Tor SOCKS stream"));
            assertTrue(diagnostic, diagnostic.matches(".* · [A-Za-z]+Exception · tunnel bytes [0-9]+↑/[0-9]+↓"));
            assertTrue("diagnostics must not expose destination host", !diagnostic.contains("private.example"));
        }
    }

    private static String readHeaders(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        int matched = 0;
        while (result.length() < 16384) {
            int value = input.read();
            if (value < 0) break;
            result.append((char) value);
            if ((matched == 0 || matched == 2) && value == '\r') matched++;
            else if ((matched == 1 || matched == 3) && value == '\n') matched++;
            else matched = value == '\r' ? 1 : 0;
            if (matched == 4) return result.toString();
        }
        throw new AssertionError("HTTP headers did not terminate");
    }

    private static void readFully(InputStream input, byte[] bytes) throws Exception {
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new AssertionError("Unexpected end of stream");
            offset += count;
        }
    }

    private static final class FakeSocks implements AutoCloseable {
        final ServerSocket server;
        final ArrayBlockingQueue<String> target = new ArrayBlockingQueue<>(1);
        final ArrayBlockingQueue<String> requestHeaders = new ArrayBlockingQueue<>(1);
        final java.util.concurrent.CountDownLatch responseReady = new java.util.concurrent.CountDownLatch(1);
        volatile InputStream tunnelInput;
        volatile OutputStream tunnelOutput;
        final Thread thread;

        FakeSocks() throws Exception {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "fake-tor-socks");
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                readFully(input, new byte[3]);
                output.write(new byte[] {5, 0});
                output.flush();
                byte[] prefix = new byte[4];
                readFully(input, prefix);
                String host;
                if (prefix[3] == 3) {
                    int length = input.read();
                    byte[] bytes = new byte[length];
                    readFully(input, bytes);
                    host = new String(bytes, StandardCharsets.US_ASCII);
                } else {
                    throw new AssertionError("Proxy must use SOCKS5 domain mode");
                }
                byte[] port = new byte[2];
                readFully(input, port);
                target.offer(host + ":" + (((port[0] & 0xff) << 8) | (port[1] & 0xff)));
                output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                output.flush();
                tunnelInput = input;
                tunnelOutput = output;
                if ("check.torproject.org:443".equals(target.peek())) {
                    byte[] ping = new byte[4];
                    readFully(input, ping);
                    output.write("pong".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                } else {
                    requestHeaders.offer(readHeaders(input));
                    responseReady.await(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
                // The test assertions report a missing target or response.
            }
        }

        @Override public void close() throws Exception { server.close(); }
    }
}

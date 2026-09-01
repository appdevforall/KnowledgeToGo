package org.appdevforall.k2go.redesign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ADFA-5361: covers the sign-in retry, which nothing else can reach.
 *
 * <p>On a real box the UI never gets here: a card whose service is down is not Ready, so it opens the
 * action sheet instead of the portal, and Get More hides the entry entirely — device-verified while
 * testing this ticket. The retry exists for the narrow race the bug actually rode in on (the service
 * falling between the probe and the tap, or answering 5xx while it is saturated by a books job), and
 * a scripted server is the only way to hold that case still.
 *
 * <p>Plain JVM, no emulator: {@code fetchCookie} touches only {@code HttpURLConnection} and
 * {@code Log} (stubbed by {@code returnDefaultValues}), so this runs in the normal unit-test task.
 */
public class AuthClientRetryTest {

    private static final String COOKIE_JSON =
            "{\"service\":\"calibre\",\"cookie\":\"session=abc123; remember_token=7|deadbeef\"}";
    private static final String ERROR_JSON = "{\"error\":\"sign-in failed\"}";
    private static final String UA = "TestConsumer/1.0";

    /** A one-connection-per-request HTTP server that answers a scripted sequence of statuses and
     *  records when each request arrived, so the test can assert both the count and the pause. */
    private static final class ScriptedBox implements AutoCloseable {
        private final ServerSocket socket;
        private final int[] statuses;
        private final String[] bodies;
        private final List<Long> arrivals = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running = true;

        ScriptedBox(int[] statuses, String[] bodies) throws IOException {
            this.statuses = statuses;
            this.bodies = bodies;
            this.socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(this::serve, "scripted-box");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/k2go-api/auth/calibre/session";
        }

        int requests() { return arrivals.size(); }

        /** Milliseconds between the first and second request; -1 when there was no second. */
        long pauseMs() { return arrivals.size() < 2 ? -1 : arrivals.get(1) - arrivals.get(0); }

        private void serve() {
            while (running) {
                try (Socket s = socket.accept()) {
                    arrivals.add(System.currentTimeMillis());
                    // Drain the request head; answering before reading it can break the client's pipe.
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { /* headers */ }

                    int i = Math.min(arrivals.size() - 1, statuses.length - 1);
                    byte[] body = bodies[i].getBytes(StandardCharsets.UTF_8);
                    OutputStream out = s.getOutputStream();
                    // Connection: close so every request is its own accept() and the count is exact.
                    out.write(("HTTP/1.1 " + statuses[i] + " Scripted\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    if (running) throw new IllegalStateException("scripted box failed", e);
                }
            }
        }

        @Override public void close() {
            running = false;
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Test public void a5xxIsRetriedAndTheSecondAnswerIsUsed() throws Exception {
        try (ScriptedBox box = new ScriptedBox(
                new int[]{503, 200}, new String[]{ERROR_JSON, COOKIE_JSON})) {
            String cookie = AuthClient.fetchCookie(box.url(), UA, "calibre");
            assertTrue("the retry's cookie must be the one returned: " + cookie,
                    cookie.contains("session=abc123"));
            assertEquals("exactly one retry", 2, box.requests());
        }
    }

    /** The defect this pins: an immediate retry asks a restarting service again too soon to get a
     *  different answer, which would make the retry decorative. */
    @Test public void theRetryPausesBeforeAskingAgain() throws Exception {
        try (ScriptedBox box = new ScriptedBox(
                new int[]{503, 200}, new String[]{ERROR_JSON, COOKIE_JSON})) {
            AuthClient.fetchCookie(box.url(), UA, "calibre");
            assertTrue("the second request came " + box.pauseMs() + "ms after the first",
                    box.pauseMs() >= 500);
        }
    }

    @Test public void aPersistent5xxGivesUpAfterOneRetry() throws Exception {
        try (ScriptedBox box = new ScriptedBox(
                new int[]{503, 503}, new String[]{ERROR_JSON, ERROR_JSON})) {
            try {
                AuthClient.fetchCookie(box.url(), UA, "calibre");
                fail("a persistent 5xx must not resolve");
            } catch (Exception expected) {
                assertTrue("the status belongs in the message: " + expected.getMessage(),
                        expected.getMessage().contains("503"));
            }
            assertEquals("one retry, never two", 2, box.requests());
        }
    }

    /** A 4xx is the box's final word — wrong credentials do not improve on a second ask, and the
     *  user is waiting behind a blocking overlay. Device-verified on a 401 (ADFA-5361). */
    @Test public void a4xxIsFinalAndNotRetried() throws Exception {
        try (ScriptedBox box = new ScriptedBox(
                new int[]{401, 200}, new String[]{ERROR_JSON, COOKIE_JSON})) {
            try {
                AuthClient.fetchCookie(box.url(), UA, "calibre");
                fail("a 401 must not resolve by retrying");
            } catch (Exception expected) {
                assertTrue("the status belongs in the message: " + expected.getMessage(),
                        expected.getMessage().contains("401"));
            }
            assertEquals("no retry on a 4xx", 1, box.requests());
        }
    }

    @Test public void a404IsFinalToo() throws Exception {
        try (ScriptedBox box = new ScriptedBox(
                new int[]{404, 200}, new String[]{ERROR_JSON, COOKIE_JSON})) {
            try {
                AuthClient.fetchCookie(box.url(), UA, "calibre");
                fail("a 404 must not resolve by retrying");
            } catch (Exception expected) {
                // the message carries the status; the point of the assert below is the count
            }
            assertEquals("no retry on a 4xx", 1, box.requests());
        }
    }

    /** A first-try success must not cost the caller a second request (or the pause). */
    @Test public void aFirstTrySuccessAsksOnce() throws Exception {
        try (ScriptedBox box = new ScriptedBox(new int[]{200}, new String[]{COOKIE_JSON})) {
            long started = System.currentTimeMillis();
            String cookie = AuthClient.fetchCookie(box.url(), UA, "calibre");
            assertTrue(cookie.contains("remember_token=7|deadbeef"));
            assertEquals(1, box.requests());
            assertTrue("a success must not pay the retry pause",
                    System.currentTimeMillis() - started < 500);
        }
    }
}

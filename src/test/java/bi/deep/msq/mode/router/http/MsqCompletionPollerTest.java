/*
 * Copyright Deep BI, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bi.deep.msq.mode.router.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import bi.deep.msq.mode.router.execution.ResultsDecorationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.Response;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.ClientResponse;
import org.apache.druid.java.util.http.client.response.HttpResponseHandler;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

class MsqCompletionPollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI BASE = URI.create("http://test/");

    @Test
    void successReturnsDecoratedResults() {
        final DummyHttpClient http = new DummyHttpClient()
                .respondGET("/druid/v2/native/statements/q1", json("{\"state\":\"SUCCESS\"}"))
                .respondGET("/druid/v2/native/statements/q1/results", bytes("[{\"c1\":1},{\"c1\":2}]"));

        final Headers headers = mock(Headers.class);

        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        final byte[] decorated = bytes(
                "[{\"version\":null,\"timestamp\":null,\"event\":{\"c1\":1}},{\"version\":null,\"timestamp\":null,\"event\":{\"c1\":2}}]");

        final Response r = MsqCompletionPoller.waitForCompletion(
                "q1", headers, http, 5L, BASE, MAPPER, deadlineNanos, ResultsDecorationStrategy.GROUP_BY);

        assertEquals(200, r.getStatus());
        assertArrayEquals(decorated, (byte[]) r.getEntity());
    }

    @Test
    void failedReturns502() {
        final DummyHttpClient http =
                new DummyHttpClient().respondGET("/druid/v2/native/statements/qf", json("{\"state\":\"FAILED\"}"));

        final Headers headers = mock(Headers.class);
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        final Response r = MsqCompletionPoller.waitForCompletion(
                "qf", headers, http, 5L, BASE, MAPPER, deadlineNanos, ResultsDecorationStrategy.GROUP_BY);

        assertEquals(502, r.getStatus());
        final String body = (String) r.getEntity();
        assertEquals("MSQ failed, check the task logs for details", body);
    }

    @Test
    void absoluteTimeoutTriggersCancelReturns504WithPayload() {
        final DummyHttpClient http = new DummyHttpClient()
                .hangGET("/druid/v2/native/statements/qt")
                .respondDELETE("/druid/v2/native/statements/qt", 200, "application/json", json("{\"cancelled\":true}"));

        final Headers headers = mock(Headers.class);
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30);

        final Response r = MsqCompletionPoller.waitForCompletion(
                "qt", headers, http, 5L, BASE, MAPPER, deadlineNanos, ResultsDecorationStrategy.GROUP_BY);

        assertEquals(504, r.getStatus());
        final Map<?, ?> body = readJson((byte[]) r.getEntity());
        assertEquals("Timeout exceeded", body.get("error"));
        assertEquals("Cancelled by timeout", body.get("action"));
        assertNotNull(body.get("cancelPayload"));
    }

    @Test
    void absoluteTimeoutCancelFailsStill504WithErrorMessage() {
        final DummyHttpClient http = new DummyHttpClient().hangGET("/druid/v2/native/statements/qc");

        final Headers headers = mock(Headers.class);
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30);

        final Response r = MsqCompletionPoller.waitForCompletion(
                "qc", headers, http, 5L, BASE, MAPPER, deadlineNanos, ResultsDecorationStrategy.GROUP_BY);

        assertEquals(504, r.getStatus());
        final Map<?, ?> body = readJson((byte[]) r.getEntity());
        assertEquals("Timeout exceeded", body.get("error"));
        assertTrue(String.valueOf(body.get("cancelPayload")).contains("errorMessage"));
    }

    @Test
    void runningThenSuccessReturns200() {
        final DummyHttpClient http = new DummyHttpClient()
                .respondGET("/druid/v2/native/statements/qrs", json("{\"state\":\"RUNNING\"}"))
                .respondGET("/druid/v2/native/statements/qrs/results", bytes("[]"));

        new Thread(
                        () -> {
                            try {
                                Thread.sleep(40);
                            } catch (InterruptedException ignored) {
                            }
                            http.respondGET("/druid/v2/native/statements/qrs", json("{\"state\":\"SUCCESS\"}"));
                            http.respondGET("/druid/v2/native/statements/qrs/results", bytes("[{\"a\":1}]"));
                        },
                        "flip-thread")
                .start();

        final Headers headers = mock(Headers.class);
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        final Response r = MsqCompletionPoller.waitForCompletion(
                "qrs", headers, http, 1, BASE, MAPPER, deadlineNanos, ResultsDecorationStrategy.GROUP_BY);

        assertEquals(200, r.getStatus());
        assertTrue(((byte[]) r.getEntity()).length > 0);
    }

    @Test
    void interruptedReturns500() {
        final DummyHttpClient http = new DummyHttpClient()
                .respondGET("/druid/v2/native/statements/qi", json("{\"state\":\"SUCCESS\"}"))
                .respondGET("/druid/v2/native/statements/qi/results", bytes("[]"));

        final Headers headers = mock(Headers.class);
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        Thread.currentThread().interrupt();

        final Response r = MsqCompletionPoller.waitForCompletion(
                "qi", headers, http, 5L, BASE, MAPPER, deadlineNanos, ResultsDecorationStrategy.GROUP_BY);

        assertEquals(500, r.getStatus());
        final String body = (String) r.getEntity();
        assertTrue(body.contains("Interrupted"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(final byte[] b) {
        try {
            return MAPPER.readValue(b, Map.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] json(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static final class DummyHttpClient implements HttpClient {

        private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
        private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
        private final Map<String, byte[]> bodies = new ConcurrentHashMap<>();
        private final Map<String, Boolean> hangs = new ConcurrentHashMap<>();

        public DummyHttpClient respondGET(
                final String path, final int status, final String contentType, final byte[] body) {
            put("GET", path, status, contentType, body, false);
            return this;
        }

        public DummyHttpClient respondGET(final String path, final byte[] body) {
            return respondGET(path, 200, "application/json", body);
        }

        public DummyHttpClient respondDELETE(
                final String path, final int status, final String contentType, final byte[] body) {
            put("DELETE", path, status, contentType, body, false);
            return this;
        }

        // incompletable future
        public DummyHttpClient hangGET(final String path) {
            put("GET", path, 200, "application/octet-stream", new byte[0], true);
            return this;
        }

        @Override
        public <Intermediate, Final> ListenableFuture<Final> go(
                final Request request, final HttpResponseHandler<Intermediate, Final> handler) {
            return go(request, handler, null);
        }

        @Override
        public <Intermediate, Final> ListenableFuture<Final> go(
                final Request request,
                final HttpResponseHandler<Intermediate, Final> handler,
                final Duration readTimeout) {
            final SettableFuture<Final> future = SettableFuture.create();
            try {
                final URI uri = request.getUrl().toURI();
                final String path = uri.getPath();
                final String method = request.getMethod().getName();
                final String key = key(method, path);

                if (Boolean.TRUE.equals(hangs.get(key))) {
                    return future;
                }

                final int status = statuses.getOrDefault(key, 404);
                final String contentType = contentTypes.getOrDefault(key, "text/plain");
                final byte[] body = bodies.getOrDefault(key, "Not Found".getBytes(StandardCharsets.UTF_8));

                final DefaultHttpResponse head =
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
                head.headers().add(HttpHeaders.Names.CONTENT_TYPE, contentType);
                head.headers().add(HttpHeaders.Names.CONTENT_LENGTH, Integer.toString(body.length));

                ClientResponse<Intermediate> cr = handler.handleResponse(head, null);

                if (body.length > 0) {
                    final HttpChunk chunk = new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(body));
                    cr = handler.handleChunk(cr, chunk, 0);
                }

                final ClientResponse<Final> done = handler.done(cr);
                future.set(done.getObj());
            } catch (final Throwable t) {
                future.setException(t);
            }
            return future;
        }

        private void put(
                final String method,
                final String path,
                final int status,
                final String contentType,
                final byte[] body,
                final boolean hang) {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            final String k = key(method, path);
            statuses.put(k, status);
            contentTypes.put(k, contentType == null ? "application/octet-stream" : contentType);
            bodies.put(k, body == null ? new byte[0] : body);
            hangs.put(k, hang);
        }

        private static String key(final String method, final String path) {
            return method + " " + path;
        }
    }
}

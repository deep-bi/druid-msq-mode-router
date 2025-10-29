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

import bi.deep.msq.mode.router.execution.ResultsDecorationStrategy;
import bi.deep.msq.mode.router.util.HttpPollUtil;
import bi.deep.msq.mode.router.util.PollSchedulerInitializer;
import bi.deep.msq.mode.router.util.ResultsDecorator;
import bi.deep.msq.mode.router.util.TimeUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.http.client.HttpClient;

public class MsqCompletionPoller {

    private static final Logger LOG = new Logger(MsqCompletionPoller.class);

    public static Response waitForCompletion(
            final String queryId,
            final Headers headers,
            final HttpClient http,
            final long pollIntervalSeconds,
            final java.net.URI base,
            final ObjectMapper mapper,
            final long deadlineNanos,
            final ResultsDecorationStrategy decorationStrategy) {
        ScheduledExecutorService scheduler = PollSchedulerInitializer.single("msq-poller-");
        CompletableFuture<Response> done = new CompletableFuture<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean inFlight = new AtomicBoolean(false);
        final long pollIntervalMillis = TimeUtil.secondsToMillis(pollIntervalSeconds);
        try {
            ScheduledFuture<?> timeout = scheduler.schedule(
                    () -> {
                        if (closed.compareAndSet(false, true)) {
                            done.complete(buildTimeoutCancelResponse(base, queryId, headers, http, mapper));
                        }
                    },
                    TimeUtil.remainingMillis(deadlineNanos),
                    TimeUnit.MILLISECONDS);

            ScheduledFuture<?> poll = scheduler.scheduleWithFixedDelay(
                    () -> {
                        if (closed.get() || !inFlight.compareAndSet(false, true)) {
                            return;
                        }
                        try {
                            long perCallMs = Math.min(TimeUtil.remainingMillis(deadlineNanos), pollIntervalMillis);
                            TaskState taskState =
                                    HttpPollUtil.fetchState(base, queryId, headers, http, perCallMs, mapper);
                            if (taskState == TaskState.SUCCESS) {
                                byte[] rows = HttpPollUtil.fetchResults(
                                        base, queryId, headers, http, TimeUtil.remainingMillis(deadlineNanos));
                                final byte[] decorated = ResultsDecorator.decorate(mapper, rows, decorationStrategy);
                                final Response response = HttpResponseBuilder.buildResult(decorated);
                                if (closed.compareAndSet(false, true)) {
                                    done.complete(response);
                                }
                            } else if (taskState == TaskState.FAILED) {
                                if (closed.compareAndSet(false, true)) {
                                    done.complete(HttpResponseBuilder.buildFailure(
                                            "MSQ failed, check the task logs for details", 502));
                                }
                            }
                        } catch (TimeoutException ignore) {
                            // tick timeout → next run
                        } catch (Throwable t) {
                            if (closed.compareAndSet(false, true)) {
                                done.complete(HttpResponseBuilder.buildFailure(t.getMessage(), 500));
                            }
                        } finally {
                            inFlight.set(false);
                        }
                    },
                    0L,
                    pollIntervalMillis,
                    TimeUnit.MILLISECONDS);

            Response result = done.get();
            poll.cancel(true);
            timeout.cancel(true);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResponseBuilder.buildFailure("Interrupted", 500);
        } catch (ExecutionException e) {
            return HttpResponseBuilder.buildFailure(e.getMessage(), 500);
        } catch (TimeoutException e) { // remainingMillis may throw before scheduling
            return buildTimeoutCancelResponse(base, queryId, headers, http, mapper);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static Response buildTimeoutCancelResponse(
            final java.net.URI base,
            final String queryId,
            final Headers headers,
            final HttpClient http,
            final com.fasterxml.jackson.databind.ObjectMapper mapper) {
        LOG.warn("Polling timed out, cancelling query %s", queryId);
        Object cancelBody = null;
        try {
            final byte[] payload = HttpPollUtil.cancelQuery(base, queryId, headers, http);
            if (payload != null && payload.length > 0) {
                try {
                    cancelBody = mapper.readTree(payload);
                } catch (Exception nonJson) {
                    cancelBody = new String(payload, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception cancelError) {
            cancelBody = Collections.singletonMap("errorMessage", cancelError.getMessage());
        }

        final Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "Timeout exceeded");
        body.put("action", "Cancelled by timeout");
        if (cancelBody != null) {
            body.put("cancelPayload", cancelBody);
        }

        try {
            final byte[] entity = mapper.writeValueAsBytes(body);
            return Response.status(504)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(entity)
                    .build();
        } catch (Exception e) {
            final String fallback = "Timeout exceeded, cancelled by timeout"
                    + (cancelBody == null ? "" : (" cancelPayload=" + cancelBody));
            return Response.status(504)
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .entity(fallback)
                    .build();
        }
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bi.deep.msq.mode.router.http;

import bi.deep.msq.mode.router.util.HttpPollUtil;
import bi.deep.msq.mode.router.util.PollSchedulerInitializer;
import bi.deep.msq.mode.router.util.TimeoutUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.ws.rs.core.Response;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.java.util.http.client.HttpClient;

public class MsqCompletionPoller {

    public static Response waitForCompletion(
            final String queryId,
            final Headers headers,
            final HttpClient http,
            final long pollIntervalMillis,
            final java.net.URI base,
            final ObjectMapper mapper,
            final long deadlineNanos) {
        ScheduledExecutorService scheduler = PollSchedulerInitializer.single("msq-poller-");
        CompletableFuture<Response> done = new CompletableFuture<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean inFlight = new AtomicBoolean(false);

        try {
            ScheduledFuture<?> timeout = scheduler.schedule(
                    () -> {
                        if (closed.compareAndSet(false, true)) {
                            done.complete(HttpResponseBuilder.buildFailure("MSQ polling timeout", 504));
                        }
                    },
                    TimeoutUtil.remainingMillis(deadlineNanos),
                    TimeUnit.MILLISECONDS);

            ScheduledFuture<?> poll = scheduler.scheduleWithFixedDelay(
                    () -> {
                        if (closed.get() || !inFlight.compareAndSet(false, true)) {
                            return;
                        }
                        try {
                            long perCallMs = Math.min(TimeoutUtil.remainingMillis(deadlineNanos), pollIntervalMillis);
                            TaskState taskState =
                                    HttpPollUtil.fetchState(base, queryId, headers, http, perCallMs, mapper);
                            if (taskState == TaskState.SUCCESS) {
                                byte[] rows = HttpPollUtil.fetchResults(
                                        base, queryId, headers, http, TimeoutUtil.remainingMillis(deadlineNanos));
                                if (closed.compareAndSet(false, true)) {
                                    done.complete(HttpResponseBuilder.buildResult(rows));
                                }
                            } else if (taskState == TaskState.FAILED) {
                                if (closed.compareAndSet(false, true)) {
                                    done.complete(HttpResponseBuilder.buildFailure("MSQ FAILED", 502));
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

            Response r = done.get();
            poll.cancel(true);
            timeout.cancel(true);
            return r;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResponseBuilder.buildFailure("Interrupted", 500);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            return HttpResponseBuilder.buildFailure(c == null ? e.toString() : c.toString(), 500);
        } catch (TimeoutException e) { // remainingMillis may throw before scheduling
            return HttpResponseBuilder.buildFailure("MSQ polling timeout", 504);
        } finally {
            scheduler.shutdownNow();
        }
    }
}

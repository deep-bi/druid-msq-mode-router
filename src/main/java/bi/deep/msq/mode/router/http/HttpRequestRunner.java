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

import bi.deep.msq.mode.router.config.TimeoutConfig;
import bi.deep.msq.mode.router.execution.ResultsDecorationStrategy;
import bi.deep.msq.mode.router.util.JsonUtil;
import bi.deep.msq.mode.router.util.TimeoutUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHandler;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHolder;

public class HttpRequestRunner {
    public static Response runRequest(final Request request, final TimeoutConfig config, final HttpClient httpClient) {
        try {
            final BytesFullResponseHolder holder = httpClient
                    .go(request, new BytesFullResponseHandler())
                    .get(config.getQueryTimeout().getMillis(), TimeUnit.MILLISECONDS);
            return parse(holder);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResponseBuilder.buildFailure("Interrupted", 500);
        } catch (final ExecutionException e) {
            return HttpResponseBuilder.buildFailure(
                    e.getCause() == null ? e.toString() : e.getCause().toString(), 500);
        } catch (final TimeoutException e) {
            return HttpResponseBuilder.buildFailure("Submit timeout", 504);
        }
    }

    public static Response runAndPoll(
            final Request submit,
            final Headers headers,
            final HttpClient httpClient,
            final TimeoutConfig config,
            final URI base,
            final ObjectMapper objectMapper,
            final ResultsDecorationStrategy decorationStrategy) {
        final long deadlineNanos = TimeoutUtil.deadlineNs(config.getQueryTimeout());
        final String queryId;
        try {
            queryId = submitMsq(submit, httpClient, objectMapper, deadlineNanos);
        } catch (final TimeoutException e) {
            return HttpResponseBuilder.buildFailure("MSQ acceptance timeout", 504);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpResponseBuilder.buildFailure("Interrupted", 500);
        } catch (final ExecutionException | IOException e) {
            final Throwable c = (e instanceof ExecutionException) ? e.getCause() : e;
            return HttpResponseBuilder.buildFailure(c == null ? e.toString() : c.toString(), 500);
        }

        return MsqCompletionPoller.waitForCompletion(
                queryId,
                headers,
                httpClient,
                config.getPollIntervalMillis(),
                base,
                objectMapper,
                deadlineNanos,
                decorationStrategy);
    }

    public static String submitMsq(
            final Request submit, final HttpClient http, final ObjectMapper objectMapper, final long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException, IOException {
        final BytesFullResponseHolder result = http.go(submit, new BytesFullResponseHandler())
                .get(TimeoutUtil.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);

        if (result == null) {
            throw new IOException("No response was provided");
        }
        if (result.getStatus().getCode() >= 300) {
            throw new IOException(bytesToString(result.getContent()));
        }

        final String queryId = JsonUtil.jsonStringField(objectMapper, result.getContent(), "queryId");
        if (queryId == null || queryId.isEmpty()) {
            throw new IOException("MSQ submit: missing queryId");
        }
        return queryId;
    }

    public static Response parse(@Nullable final BytesFullResponseHolder result) {
        if (result == null) {
            return HttpResponseBuilder.buildFailure("No response was provided", 400);
        } else if (result.getStatus().getCode() < 300) {
            return HttpResponseBuilder.buildResult(result.getContent());
        } else if (ArrayUtils.isEmpty(result.getContent())) {
            return HttpResponseBuilder.buildFailure(
                    "Empty response received, something went wrong",
                    result.getStatus().getCode());
        } else {
            return HttpResponseBuilder.buildFailure(bytesToString(result.getContent()), 400);
        }
    }

    private static String bytesToString(final byte[] b) {
        return (b == null || b.length == 0) ? "" : new String(b, StandardCharsets.UTF_8);
    }
}

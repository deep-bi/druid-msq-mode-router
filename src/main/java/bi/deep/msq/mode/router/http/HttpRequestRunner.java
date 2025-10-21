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

import com.google.common.util.concurrent.ListenableFuture;
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
import org.joda.time.Duration;

public class HttpRequestRunner {

    public static Response runRequest(
            final Request request, @Nullable Duration patience, HttpClient httpClient, String queryId) {
        ListenableFuture<BytesFullResponseHolder> future = httpClient.go(request, new BytesFullResponseHandler());
        try {
            if (patience != null) {
                BytesFullResponseHolder holder = future.get(patience.getMillis(), TimeUnit.MILLISECONDS);
                return parse(holder);
            }
            return HttpResponseBuilder.buildInProgress(queryId);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public static Response parse(@Nullable BytesFullResponseHolder result) {
        if (result == null) {
            return HttpResponseBuilder.buildFailure("No response was provided", 400);
        } else if (result.getStatus().getCode() < 300) {
            return HttpResponseBuilder.buildResult(result.getContent());
        } else if (ArrayUtils.isEmpty(result.getContent())) {
            return HttpResponseBuilder.buildFailure(
                    "Empty response received, something went wrong",
                    result.getStatus().getCode());
        } else {
            return HttpResponseBuilder.buildFailure(new String(result.getContent(), StandardCharsets.UTF_8), 400);
        }
    }
}

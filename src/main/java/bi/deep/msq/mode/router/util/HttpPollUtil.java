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
package bi.deep.msq.mode.router.util;

import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.Headers;
import bi.deep.msq.mode.router.http.HttpRequestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.druid.indexer.TaskState;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHandler;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHolder;

public class HttpPollUtil {

    public static TaskState fetchState(
            final URI base,
            final String qid,
            final Headers headers,
            final HttpClient http,
            final long timeoutMs,
            final ObjectMapper mapper)
            throws ExecutionException, InterruptedException, IOException, TimeoutException {
        URL url = base.resolve(ApiPaths.MSQ_QUERY + "/" + qid).toURL();
        Request get = HttpRequestFactory.buildInternalGet(url, headers);
        BytesFullResponseHolder h = http.go(get, new BytesFullResponseHandler()).get(timeoutMs, TimeUnit.MILLISECONDS);

        if (h == null || h.getStatus().getCode() >= 300) {
            return TaskState.RUNNING;
        }

        String state = JsonUtil.jsonStringField(mapper, h.getContent(), "state");
        if (state == null) {
            return TaskState.RUNNING;
        }

        switch (state) {
            case "SUCCESS":
                return TaskState.SUCCESS;
            case "FAILED":
            case "CANCELED":
            case "CANCELLED":
                return TaskState.FAILED;
            default:
                return TaskState.RUNNING;
        }
    }

    public static byte[] fetchResults(
            final URI base, final String qid, final Headers headers, final HttpClient http, final long timeoutMs)
            throws ExecutionException, InterruptedException, IOException, TimeoutException {
        URL url = base.resolve(ApiPaths.MSQ_QUERY + "/" + qid + "/results").toURL();
        Request get = HttpRequestFactory.buildInternalGet(url, headers);
        BytesFullResponseHolder h = http.go(get, new BytesFullResponseHandler()).get(timeoutMs, TimeUnit.MILLISECONDS);

        if (h == null) {
            throw new IOException("Empty MSQ results");
        }
        if (h.getStatus().getCode() >= 300) {
            throw new IOException("MSQ results " + h.getStatus().getCode());
        }
        return h.getContent();
    }

    public static byte[] cancelQuery(final URI base, final String qid, final Headers headers, final HttpClient http)
            throws ExecutionException, InterruptedException, IOException, TimeoutException {
        URL url = base.resolve(ApiPaths.MSQ_QUERY + "/" + qid).toURL();
        Request delete = HttpRequestFactory.buildInternalDelete(url, headers);
        BytesFullResponseHolder h =
                http.go(delete, new BytesFullResponseHandler()).get(5000, TimeUnit.MILLISECONDS);
        if (h == null) {
            throw new IOException("Empty MSQ cancel response");
        }
        if (h.getStatus().getCode() >= 300) {
            throw new IOException(
                    "MSQ cancel failed with status: " + h.getStatus().getCode());
        }
        return h.getContent();
    }
}

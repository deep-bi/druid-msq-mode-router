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
package bi.deep.msq.mode.router.execution;

import bi.deep.msq.mode.router.config.TimeoutConfig;
import bi.deep.msq.mode.router.http.Headers;
import bi.deep.msq.mode.router.http.HttpRequestRunner;
import bi.deep.msq.mode.router.http.HttpResponseBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.query.Query;

public abstract class BaseQueryExecutor implements QueryExecutor {
    protected final ObjectMapper jsonMapper;
    protected final HttpClient httpClient;

    protected BaseQueryExecutor(ObjectMapper jsonMapper, HttpClient httpClient) {
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
    }

    @Override
    public Response execute(
            URI base, Query<?> query, HttpServletRequest req, TimeoutConfig config, SubmissionMode submissionMode) {
        try {
            Query<?> prepared = prepareQuery(query);

            byte[] payload = jsonMapper.writeValueAsBytes(query);
            Headers headers = Headers.snapshot(req);
            Request request = buildRequest(base, payload, headers);

            if (shouldPoll(submissionMode)) {
                return HttpRequestRunner.runAndPoll(
                        request, headers, httpClient, config, base, jsonMapper, resultsDecorationStrategy(prepared));
            }

            return HttpRequestRunner.runRequest(request, config, httpClient);

        } catch (IOException ex) {
            return HttpResponseBuilder.buildFailure(ex.getMessage(), 400);
        }
    }

    protected Query<?> prepareQuery(Query<?> query) {
        return query;
    }

    protected boolean shouldPoll(SubmissionMode submissionMode) {
        return false;
    }

    protected ResultsDecorationStrategy resultsDecorationStrategy(Query<?> query) {
        return ResultsDecorationStrategy.NONE;
    }

    protected abstract Request buildRequest(URI base, byte[] payload, Headers headers) throws IOException;
}

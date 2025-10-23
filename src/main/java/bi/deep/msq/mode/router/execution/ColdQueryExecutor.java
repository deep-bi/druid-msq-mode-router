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

import static org.apache.druid.query.Query.GROUP_BY;
import static org.apache.druid.query.Query.SCAN;

import bi.deep.msq.mode.router.config.TimeoutConfig;
import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.Headers;
import bi.deep.msq.mode.router.http.HttpRequestFactory;
import bi.deep.msq.mode.router.http.HttpRequestRunner;
import bi.deep.msq.mode.router.http.HttpResponseBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.query.Query;
import org.apache.druid.query.scan.ScanQuery;

public class ColdQueryExecutor extends BaseQueryExecutor {

    public ColdQueryExecutor(ObjectMapper jsonMapper, HttpClient httpClient) {
        super(jsonMapper, httpClient);
    }

    @Override
    public Response execute(
            URI base, Query<?> query, HttpServletRequest req, TimeoutConfig config, SubmissionMode submissionMode) {
        Query<?> prepared = enrichContext(query);
        if (submissionMode == SubmissionMode.WAIT_FOR_COMPLETION) {
            try {
                byte[] payload = jsonMapper.writeValueAsBytes(prepared);
                Headers headers = Headers.snapshot(req);
                Request request = buildRequest(base, payload, headers);
                return HttpRequestRunner.runAndPoll(
                        request, headers, httpClient, config, base, jsonMapper, decideResultsDecorationStrategy(query));
            } catch (IOException ex) {
                return HttpResponseBuilder.buildFailure(ex.getMessage(), 400);
            }
        }
        return super.execute(base, prepared, req, config, submissionMode);
    }

    @Override
    protected Request buildRequest(URI base, byte[] payload, Headers headers) throws IOException {
        URL url = base.resolve(ApiPaths.MSQ_QUERY + "/").toURL();
        return HttpRequestFactory.buildInternalPost(url, payload, headers);
    }

    protected Query<?> enrichContext(Query<?> query) {
        Map<String, Object> ctx;
        if (query.getContext() == null) {
            ctx = new HashMap<>();
        } else {
            ctx = new HashMap<>(query.getContext());
        }
        if (!ctx.containsKey("executionMode")) {
            ctx.put("executionMode", "ASYNC");
            return query.withOverriddenContext(ctx);
        }
        return query;
    }

    private ResultsDecorationStrategy decideResultsDecorationStrategy(final Query<?> query) {
        if (query.getType().equals(GROUP_BY)) {
            return ResultsDecorationStrategy.GROUP_BY;
        } else if (query.getType().equals(SCAN)
                && !((ScanQuery) query).getTimeOrder().equals(ScanQuery.Order.NONE)) {
            return ResultsDecorationStrategy.ORDERED_SCAN;
        }
        return ResultsDecorationStrategy.NONE;
    }
}

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
package bi.deep.msq.mode.router.execution;

import bi.deep.msq.mode.router.config.TimeoutConfig;
import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.Headers;
import bi.deep.msq.mode.router.http.HttpRequestFactory;
import bi.deep.msq.mode.router.http.HttpRequestRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import javax.ws.rs.core.Response;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;

/**
 * Executes SQL queries through the MSQ async statements endpoint ({@code /druid/v2/sql/statements}).
 * Injects {@code executionMode: ASYNC} into the SQL context so Druid accepts the query, then polls
 * for completion using the same state-machine as native MSQ cold queries.
 */
public class SqlColdQueryExecutor {

    private final ObjectMapper jsonMapper;
    private final HttpClient httpClient;

    public SqlColdQueryExecutor(ObjectMapper jsonMapper, HttpClient httpClient) {
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
    }

    public Response execute(URI base, byte[] body, Headers headers, TimeoutConfig config) throws IOException {
        byte[] prepared = withAsyncContext(body);
        URL url = base.resolve(ApiPaths.SQL_MSQ_QUERY).toURL();
        Request request = HttpRequestFactory.buildInternalPost(url, prepared, headers);
        return HttpRequestRunner.runAndPoll(
                request,
                headers,
                httpClient,
                config,
                base,
                jsonMapper,
                ResultsDecorationStrategy.NONE,
                ApiPaths.SQL_MSQ_QUERY);
    }

    private byte[] withAsyncContext(byte[] body) throws IOException {
        ObjectNode root = (ObjectNode) jsonMapper.readTree(body);
        ObjectNode context = root.has("context") && root.get("context").isObject()
                ? (ObjectNode) root.get("context")
                : jsonMapper.createObjectNode();

        if (!context.has("executionMode")) {
            context.put("executionMode", "ASYNC");
        }

        root.set("context", context);
        return jsonMapper.writeValueAsBytes(root);
    }
}

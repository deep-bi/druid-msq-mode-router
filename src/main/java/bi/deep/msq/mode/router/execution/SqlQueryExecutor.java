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
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import javax.ws.rs.core.Response;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;

public class SqlQueryExecutor {

    private final HttpClient httpClient;

    public SqlQueryExecutor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public Response execute(URI base, byte[] body, Headers headers, TimeoutConfig config) throws IOException {
        URL url = base.resolve(ApiPaths.SQL_QUERY).toURL();
        Request request = HttpRequestFactory.buildInternalPost(url, body, headers);
        return HttpRequestRunner.runRequest(request, config, httpClient);
    }
}

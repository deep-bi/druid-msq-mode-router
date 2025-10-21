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
import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.druid.client.selector.Server;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHandler;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHolder;

public class HttpRequestForwarder {

    public static Response forward(
            final HttpServletRequest req,
            final UriInfo uriInfo,
            Server server,
            byte[] body,
            final HttpClient httpClient)
            throws MalformedURLException, ExecutionException, InterruptedException {
        URI address = serverToUri(server);
        URI path = uriInfo.getBaseUri().relativize(uriInfo.getRequestUri());
        URI uri = address.resolve(path);

        Request request = HttpRequestFactory.buildInternalRequest(uri.toURL(), body, req);
        ListenableFuture<BytesFullResponseHolder> future = httpClient.go(request, new BytesFullResponseHandler());

        return HttpResponseBuilder.buildResponseFromFuture(future);
    }

    public static URI serverToUri(Server server) {
        return URI.create(server.getScheme() + "://" + server.getHost() + "/");
    }
}

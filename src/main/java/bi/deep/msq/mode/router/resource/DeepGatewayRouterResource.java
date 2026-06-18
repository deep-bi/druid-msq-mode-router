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
package bi.deep.msq.mode.router.resource;

import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.HttpRequestForwarder;
import bi.deep.msq.mode.router.security.Authorizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.apache.druid.client.selector.Server;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.annotations.EscalatedGlobal;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.query.Query;
import org.apache.druid.server.router.QueryHostFinder;

@LazySingleton
@Path(ApiPaths.ROUTER_V2)
public class DeepGatewayRouterResource {
    private final Authorizer authorizer;
    private final QueryHostFinder queryHostFinder;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public DeepGatewayRouterResource(
            Authorizer authorizer,
            QueryHostFinder queryHostFinder,
            @EscalatedGlobal HttpClient httpClient,
            @Json ObjectMapper objectMapper) {
        this.authorizer = authorizer;
        this.queryHostFinder = queryHostFinder;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postQuery(byte[] body, @Context final HttpServletRequest req, @Context final UriInfo uriInfo)
            throws IOException, ExecutionException, InterruptedException {
        authorizer.authorize(req);

        JsonNode root = objectMapper.readTree(body);
        Server server;

        if (root.has("queryType")) {
            Query<?> query = objectMapper.treeToValue(root, Query.class);
            server = queryHostFinder.pickServer(query);
        } else {
            server = queryHostFinder.pickDefaultServer();
        }

        return HttpRequestForwarder.forward(req, uriInfo, server, body, httpClient);
    }
}

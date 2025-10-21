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
package bi.deep.msq.mode.router.resource;

import bi.deep.msq.mode.router.config.DeepGatewayConfig;
import bi.deep.msq.mode.router.execution.AsyncMode;
import bi.deep.msq.mode.router.execution.ColdQueryExecutor;
import bi.deep.msq.mode.router.execution.ExecutionMode;
import bi.deep.msq.mode.router.execution.ExecutionModeSelector;
import bi.deep.msq.mode.router.execution.HotQueryExecutor;
import bi.deep.msq.mode.router.execution.QueryDispatcher;
import bi.deep.msq.mode.router.execution.QueryExecutor;
import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.HttpResponseBuilder;
import bi.deep.msq.mode.router.security.Authorizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.druid.client.BrokerServerView;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.annotations.EscalatedClient;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.guice.annotations.Self;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.DataSource;
import org.apache.druid.server.DruidNode;
import org.apache.druid.server.security.ForbiddenException;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.joda.time.Duration;

@LazySingleton
@Path(ApiPaths.BASE)
public class DeepGatewayBrokerResource {

    private static final Logger LOGGER = new Logger(DeepGatewayBrokerResource.class);
    private final DruidNode self;
    private final Authorizer authorizer;
    private final ObjectMapper jsonMapper;
    private final BrokerServerView brokerServerView;
    private final AsyncMode asyncMode;
    private final QueryDispatcher queryDispatcher;

    @Inject
    public DeepGatewayBrokerResource(
            @Self DruidNode self,
            Authorizer authorizer,
            @Json ObjectMapper jsonMapper,
            @EscalatedClient HttpClient httpClient,
            BrokerServerView brokerServerView,
            DeepGatewayConfig config) {
        this.self = self;
        this.authorizer = authorizer;
        this.jsonMapper = jsonMapper;
        this.brokerServerView = brokerServerView;
        this.asyncMode = new AsyncMode(config.getDefaultMode());
        Map<ExecutionMode, QueryExecutor> executors = new HashMap<>();
        executors.put(ExecutionMode.HOT, new HotQueryExecutor(jsonMapper, httpClient));
        executors.put(ExecutionMode.COLD, new ColdQueryExecutor(jsonMapper, httpClient));
        this.queryDispatcher = new QueryDispatcher(executors);
    }

    @POST
    @Path(ApiPaths.DRUID_V2)
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postQuery(
            byte[] body, @QueryParam("mode") final String mode, @Context final HttpServletRequest req) {
        try {
            authorizer.authorize(req);

            BaseQuery<?> query = jsonMapper.readValue(body, BaseQuery.class);

            DataSource dataSource = query.getDataSource();
            Optional<VersionedIntervalTimeline<String, ServerSelector>> maybeTimeline =
                    brokerServerView.getTimeline(dataSource.getAnalysis());

            ExecutionMode selectedMode = ExecutionModeSelector.select(query.getIntervals(), maybeTimeline.orElse(null));

            // Not going to work for the msq just yet, as it's always returns the query id in response , adding a
            // placeholder here
            Optional<Duration> patience =
                    selectedMode == ExecutionMode.COLD ? asyncMode.parse(mode) : Optional.of(Duration.standardDays(1));

            LOGGER.info("Query received: %s, selected mode: %s", query.getType(), selectedMode);

            return queryDispatcher.dispatch(selectedMode, self.getUriToUse(), query, req, patience.orElse(null));
        } catch (JsonProcessingException ex) {
            return HttpResponseBuilder.buildFailure("Invalid query JSON: " + ex.getOriginalMessage(), 400);
        } catch (ForbiddenException ex) {
            return HttpResponseBuilder.buildFailure("Forbidden", 403);
        } catch (Exception ex) {
            return HttpResponseBuilder.buildFailure(ex.getMessage(), 500);
        }
    }
}

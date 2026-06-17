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

import bi.deep.msq.mode.router.config.TimeoutConfig;
import bi.deep.msq.mode.router.execution.ColdQueryExecutor;
import bi.deep.msq.mode.router.execution.ExecutionMode;
import bi.deep.msq.mode.router.execution.ExecutionModeSelector;
import bi.deep.msq.mode.router.execution.HotQueryExecutor;
import bi.deep.msq.mode.router.execution.QueryDispatcher;
import bi.deep.msq.mode.router.execution.QueryExecutor;
import bi.deep.msq.mode.router.execution.SqlColdQueryExecutor;
import bi.deep.msq.mode.router.execution.SqlIntervalExtractor;
import bi.deep.msq.mode.router.execution.SqlQueryExecutor;
import bi.deep.msq.mode.router.execution.SubmissionMode;
import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.Headers;
import bi.deep.msq.mode.router.http.HttpResponseBuilder;
import bi.deep.msq.mode.router.security.Authorizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
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
import org.apache.druid.query.Query;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.server.DruidNode;
import org.apache.druid.server.security.ForbiddenException;
import org.apache.druid.timeline.VersionedIntervalTimeline;

@LazySingleton
@Path(ApiPaths.ROUTER_V2)
public class DeepGatewayBrokerResource {

    private static final Logger LOGGER = new Logger(DeepGatewayBrokerResource.class);
    private final DruidNode self;
    private final Authorizer authorizer;
    private final ObjectMapper jsonMapper;
    private final BrokerServerView brokerServerView;
    private final SqlQueryExecutor sqlQueryExecutor;
    private final SqlColdQueryExecutor sqlColdQueryExecutor;
    private final QueryDispatcher queryDispatcher;
    private final TimeoutConfig timeoutConfig;

    @Inject
    public DeepGatewayBrokerResource(
            @Self DruidNode self,
            Authorizer authorizer,
            @Json ObjectMapper jsonMapper,
            @EscalatedClient HttpClient httpClient,
            BrokerServerView brokerServerView,
            TimeoutConfig timeoutConfig) {
        this(
                self,
                authorizer,
                jsonMapper,
                brokerServerView,
                new SqlQueryExecutor(httpClient),
                new SqlColdQueryExecutor(jsonMapper, httpClient),
                buildDispatcher(jsonMapper, httpClient),
                timeoutConfig);
    }

    // Visible for testing
    DeepGatewayBrokerResource(
            DruidNode self,
            Authorizer authorizer,
            ObjectMapper jsonMapper,
            BrokerServerView brokerServerView,
            SqlQueryExecutor sqlQueryExecutor,
            SqlColdQueryExecutor sqlColdQueryExecutor,
            QueryDispatcher queryDispatcher,
            TimeoutConfig timeoutConfig) {
        this.self = self;
        this.authorizer = authorizer;
        this.jsonMapper = jsonMapper;
        this.brokerServerView = brokerServerView;
        this.sqlQueryExecutor = sqlQueryExecutor;
        this.sqlColdQueryExecutor = sqlColdQueryExecutor;
        this.queryDispatcher = queryDispatcher;
        this.timeoutConfig = timeoutConfig;
    }

    private static QueryDispatcher buildDispatcher(ObjectMapper jsonMapper, HttpClient httpClient) {
        Map<ExecutionMode, QueryExecutor> executors = Map.of(
                ExecutionMode.HOT, new HotQueryExecutor(jsonMapper, httpClient),
                ExecutionMode.COLD, new ColdQueryExecutor(jsonMapper, httpClient));

        return new QueryDispatcher(executors);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postQuery(
            byte[] body, @QueryParam("mode") final String mode, @Context final HttpServletRequest req) {
        try {
            authorizer.authorize(req);

            JsonNode root = jsonMapper.readTree(body);

            if (!root.has("queryType")) {
                return routeSqlQuery(root, body, req);
            }

            BaseQuery<?> query = jsonMapper.treeToValue(root, BaseQuery.class);
            ExecutionMode selectedMode;

            if (Query.SEGMENT_METADATA.equals(query.getType())) {
                LOGGER.info("segmentMetadata query received, routing HOT");
                selectedMode = ExecutionMode.HOT;
            } else if (!Query.SCAN.equals(query.getType())) {
                LOGGER.info("Query type %s not supported by cold mode, routing HOT", query.getType());
                selectedMode = ExecutionMode.HOT;
            } else {
                Optional<VersionedIntervalTimeline<String, ServerSelector>> maybeTimeline =
                        brokerServerView.getTimeline(query.getDataSource().getAnalysis());
                selectedMode = ExecutionModeSelector.select(query.getIntervals(), maybeTimeline.orElse(null));
            }

            // Hot queries always use sync mode
            SubmissionMode submissionMode =
                    selectedMode == ExecutionMode.COLD ? SubmissionMode.fromString(mode) : SubmissionMode.SYNC;

            LOGGER.info("Query received: %s, selected mode: %s", query.getType(), selectedMode);

            return queryDispatcher.dispatch(
                    selectedMode, self.getUriToUse(), query, req, timeoutConfig, submissionMode);
        } catch (JsonProcessingException ex) {
            return HttpResponseBuilder.buildFailure("Invalid query JSON: " + ex.getOriginalMessage(), 400);
        } catch (ForbiddenException ex) {
            return HttpResponseBuilder.buildFailure("Forbidden", 403);
        } catch (Exception ex) {
            return HttpResponseBuilder.buildFailure(ex.getMessage(), 500);
        }
    }

    private Response routeSqlQuery(JsonNode root, byte[] body, HttpServletRequest req) throws Exception {
        String sqlText = root.path("query").asText(null);
        if (sqlText != null) {
            SqlIntervalExtractor.Result extracted = sqlText.contains("__time")
                    ? SqlIntervalExtractor.extract(sqlText)
                    : SqlIntervalExtractor.Result.EMPTY;
            if (!extracted.intervals.isEmpty()) {
                if (extracted.dataSource != null) {
                    Optional<VersionedIntervalTimeline<String, ServerSelector>> maybeTimeline =
                            brokerServerView.getTimeline(new TableDataSource(extracted.dataSource).getAnalysis());
                    if (ExecutionModeSelector.select(extracted.intervals, maybeTimeline.orElse(null))
                            == ExecutionMode.HOT) {
                        LOGGER.info("SQL hot path (interval in timeline), routing to %s", ApiPaths.SQL_QUERY);
                        return sqlQueryExecutor.execute(self.getUriToUse(), body, Headers.snapshot(req), timeoutConfig);
                    }
                }
                // Interval present but outside timeline, or datasource unextractable → COLD
                LOGGER.info("SQL cold path (interval outside timeline), routing to %s", ApiPaths.SQL_MSQ_QUERY);
            } else {
                // No time filter → query wants all history, must include cold storage
                LOGGER.info("SQL cold path (no time filter), routing to %s", ApiPaths.SQL_MSQ_QUERY);
            }
        } else {
            LOGGER.info("SQL cold path (no query text), routing to %s", ApiPaths.SQL_MSQ_QUERY);
        }
        return sqlColdQueryExecutor.execute(self.getUriToUse(), body, Headers.snapshot(req), timeoutConfig);
    }
}

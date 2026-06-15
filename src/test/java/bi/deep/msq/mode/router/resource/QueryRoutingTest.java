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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bi.deep.msq.mode.router.config.TimeoutConfig;
import bi.deep.msq.mode.router.execution.ExecutionMode;
import bi.deep.msq.mode.router.execution.QueryDispatcher;
import bi.deep.msq.mode.router.execution.SqlColdQueryExecutor;
import bi.deep.msq.mode.router.execution.SqlQueryExecutor;
import bi.deep.msq.mode.router.helpers.TimelineCreator;
import bi.deep.msq.mode.router.security.Authorizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.apache.druid.client.BrokerServerView;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.ClientResponse;
import org.apache.druid.java.util.http.client.response.HttpResponseHandler;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.Query;
import org.apache.druid.server.DruidNode;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryRoutingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private Authorizer authorizer;

    @Mock
    private BrokerServerView brokerServerView;

    @Mock
    private DruidNode self;

    @Mock
    private TimeoutConfig timeoutConfig;

    @Mock
    private QueryDispatcher queryDispatcher;

    private RecordingHttpClient httpClient;
    private HttpServletRequest mockReq;

    @BeforeEach
    void setUp() {
        // lenient: not every routing path reaches getUriToUse() (e.g. SQL cold paths bypass the dispatcher)
        lenient().when(self.getUriToUse()).thenReturn(URI.create("http://localhost:8082/"));
        doNothing().when(authorizer).authorize(any());
        httpClient = new RecordingHttpClient();
        mockReq = mock(HttpServletRequest.class);
        // lenient: Headers.snapshot(req) is only reached in paths that actually proxy a request
        lenient().when(mockReq.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    }

    @Test
    void sqlRequestWithNoIntervalRoutesToColdEndpoint() throws Exception {
        SqlQueryExecutor sqlExecutor = mock(SqlQueryExecutor.class);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);
        when(sqlColdExecutor.execute(any(), any(), any(), any()))
                .thenReturn(Response.ok("[]").build());
        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                MAPPER,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        resource.postQuery("{\"query\":\"SELECT 1\"}".getBytes(StandardCharsets.UTF_8), null, mockReq);

        verify(sqlColdExecutor).execute(any(), any(), any(), any());
        verifyNoInteractions(sqlExecutor);
        verifyNoInteractions(queryDispatcher);
    }

    @Test
    void segmentMetadataIsAlwaysRoutedHot() throws Exception {
        ObjectMapper mockMapper = nativeQueryMapper("segmentMetadata");
        when(queryDispatcher.dispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(Response.ok("{}").build());
        SqlQueryExecutor sqlExecutor = new SqlQueryExecutor(httpClient);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);
        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                mockMapper,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        resource.postQuery("{\"queryType\":\"segmentMetadata\"}".getBytes(StandardCharsets.UTF_8), null, mockReq);

        ArgumentCaptor<ExecutionMode> modeCaptor = ArgumentCaptor.forClass(ExecutionMode.class);
        verify(queryDispatcher).dispatch(modeCaptor.capture(), any(), any(), any(), any(), any());
        assertEquals(ExecutionMode.HOT, modeCaptor.getValue());
        verify(brokerServerView, never()).getTimeline(any());
    }

    @Test
    void unsupportedQueryTypeForColdModeFallsBackToHot() throws Exception {
        ObjectMapper mockMapper = nativeQueryMapper("timeseries");
        when(brokerServerView.getTimeline(any())).thenReturn(Optional.empty());
        when(queryDispatcher.dispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(Response.ok("{}").build());
        SqlQueryExecutor sqlExecutor = new SqlQueryExecutor(httpClient);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);
        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                mockMapper,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        resource.postQuery("{\"queryType\":\"timeseries\"}".getBytes(StandardCharsets.UTF_8), null, mockReq);

        ArgumentCaptor<ExecutionMode> modeCaptor = ArgumentCaptor.forClass(ExecutionMode.class);
        verify(queryDispatcher).dispatch(modeCaptor.capture(), any(), any(), any(), any(), any());
        assertEquals(ExecutionMode.HOT, modeCaptor.getValue());
    }

    @Test
    void scanQueryRoutingDelegatesToTimeline() throws Exception {
        ObjectMapper mockMapper = nativeQueryMapper(Query.SCAN);
        when(brokerServerView.getTimeline(any())).thenReturn(Optional.empty());
        when(queryDispatcher.dispatch(any(), any(), any(), any(), any(), any()))
                .thenReturn(Response.ok("{}").build());
        SqlQueryExecutor sqlExecutor = new SqlQueryExecutor(httpClient);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);
        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                mockMapper,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        resource.postQuery("{\"queryType\":\"scan\"}".getBytes(StandardCharsets.UTF_8), null, mockReq);

        ArgumentCaptor<ExecutionMode> modeCaptor = ArgumentCaptor.forClass(ExecutionMode.class);
        verify(queryDispatcher).dispatch(modeCaptor.capture(), any(), any(), any(), any(), any());
        assertEquals(ExecutionMode.HOT, modeCaptor.getValue());
        assertNull(httpClient.lastPath(), "scan goes through dispatcher, not HTTP client directly");
    }

    @Test
    void sqlWithNoTimeFilterRoutesToColdSqlEndpoint() throws Exception {
        SqlQueryExecutor sqlExecutor = mock(SqlQueryExecutor.class);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);
        when(sqlColdExecutor.execute(any(), any(), any(), any()))
                .thenReturn(Response.ok("[]").build());
        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                MAPPER,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        String body = "{\"query\":\"SELECT COUNT(*) FROM myTable\"}";
        resource.postQuery(body.getBytes(StandardCharsets.UTF_8), null, mockReq);

        // No interval → always COLD (must include cold storage history)
        verify(sqlColdExecutor).execute(any(), any(), any(), any());
        verifyNoInteractions(sqlExecutor);
        verifyNoInteractions(queryDispatcher);
    }

    @Test
    void sqlWithIntervalInTimelineRoutesToHotSqlEndpoint() throws Exception {
        when(timeoutConfig.getQueryTimeout()).thenReturn(Duration.standardMinutes(1));
        SqlQueryExecutor sqlExecutor = new SqlQueryExecutor(httpClient);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);

        // Timeline covers 2024–2026; the query interval [2025-01-01, 2025-04-01] is inside → HOT
        Interval timelineSpan = new Interval(
                new DateTime(2024, 1, 1, 0, 0, DateTimeZone.UTC), new DateTime(2026, 1, 1, 0, 0, DateTimeZone.UTC));
        VersionedIntervalTimeline<String, ServerSelector> timeline =
                TimelineCreator.ofSinglePartition("v1", timelineSpan);
        when(brokerServerView.getTimeline(any())).thenReturn(Optional.of(timeline));

        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                MAPPER,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        String body =
                "{\"query\":\"SELECT * FROM myTable" + " WHERE __time >= '2025-01-01' AND __time < '2025-04-01'\"}";
        resource.postQuery(body.getBytes(StandardCharsets.UTF_8), null, mockReq);

        assertEquals("/druid/v2/sql", httpClient.lastPath());
        verifyNoInteractions(sqlColdExecutor);
        verifyNoInteractions(queryDispatcher);
    }

    @Test
    void sqlWithIntervalOutsideTimelineRoutesToColdSqlEndpoint() throws Exception {
        SqlQueryExecutor sqlExecutor = mock(SqlQueryExecutor.class);
        SqlColdQueryExecutor sqlColdExecutor = mock(SqlColdQueryExecutor.class);
        when(sqlColdExecutor.execute(any(), any(), any(), any()))
                .thenReturn(Response.ok("[]").build());

        // Empty timeline → all intervals are outside → COLD
        when(brokerServerView.getTimeline(any())).thenReturn(Optional.empty());

        DeepGatewayBrokerResource resource = new DeepGatewayBrokerResource(
                self,
                authorizer,
                MAPPER,
                brokerServerView,
                sqlExecutor,
                sqlColdExecutor,
                queryDispatcher,
                timeoutConfig);

        String body =
                "{\"query\":\"SELECT * FROM myTable" + " WHERE __time >= '2020-01-01' AND __time < '2020-04-01'\"}";
        Response response = resource.postQuery(body.getBytes(StandardCharsets.UTF_8), null, mockReq);

        assertEquals(200, response.getStatus());
        verify(sqlColdExecutor).execute(any(), any(), any(), any());
        verifyNoInteractions(sqlExecutor);
        verifyNoInteractions(queryDispatcher);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ObjectMapper nativeQueryMapper(String queryType) throws Exception {
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("queryType", queryType);
        when(mockMapper.readTree(any(byte[].class))).thenReturn(root);

        BaseQuery<?> mockQuery = mock(BaseQuery.class);
        when(mockQuery.getType()).thenReturn(queryType);
        // lenient: segmentMetadata short-circuits before these are called
        lenient().when(mockQuery.getIntervals()).thenReturn(Collections.emptyList());
        DataSource mockDs = mock(DataSource.class);
        lenient().when(mockQuery.getDataSource()).thenReturn(mockDs);
        doReturn(mockQuery).when(mockMapper).treeToValue(any(), eq(BaseQuery.class));

        return mockMapper;
    }

    private static final class RecordingHttpClient implements HttpClient {
        private URL lastUrl;

        @Override
        public <I, F> ListenableFuture<F> go(Request request, HttpResponseHandler<I, F> handler) {
            return go(request, handler, null);
        }

        @Override
        public <Intermediate, Final> ListenableFuture<Final> go(
                Request request, HttpResponseHandler<Intermediate, Final> handler, Duration readTimeout) {
            lastUrl = request.getUrl();
            SettableFuture<Final> future = SettableFuture.create();
            try {
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                head.headers().add(HttpHeaders.Names.CONTENT_TYPE, "application/json");
                head.headers().add(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(body.length));
                ClientResponse<Intermediate> cr = handler.handleResponse(head, null);
                HttpChunk chunk = new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(body));
                cr = handler.handleChunk(cr, chunk, 0);
                ClientResponse<Final> done = handler.done(cr);
                future.set(done.getObj());
            } catch (Throwable t) {
                future.setException(t);
            }
            return future;
        }

        String lastPath() {
            return lastUrl == null ? null : lastUrl.getPath();
        }
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bi.deep.msq.mode.router.helpers.FakeInventoryView;
import java.util.Collections;
import java.util.Optional;
import org.apache.druid.client.BrokerSegmentWatcherConfig;
import org.apache.druid.client.BrokerServerView;
import org.apache.druid.client.DirectDruidClientFactory;
import org.apache.druid.client.DruidServer;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.client.selector.TierSelectorStrategy;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.joda.time.Interval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ExecutionModeSelectorIT {
    private static final String DATASOURCE = "ds";

    private FakeInventoryView inventory;
    private BrokerServerView brokerView;
    private DruidServer historical;

    @BeforeEach
    void setUp() {
        inventory = new FakeInventoryView();

        DirectDruidClientFactory direct = Mockito.mock(DirectDruidClientFactory.class);
        TierSelectorStrategy tierStrategy = Mockito.mock(TierSelectorStrategy.class);
        ServiceEmitter emitter = Mockito.mock(ServiceEmitter.class);
        BrokerSegmentWatcherConfig watcherCfg = new BrokerSegmentWatcherConfig();

        brokerView = new BrokerServerView(direct, inventory, tierStrategy, emitter, watcherCfg);

        historical = new DruidServer("hist-1", "host:8083", null, 1_000_000_000L, ServerType.HISTORICAL, "tier-1", 0);
        inventory.addServer(historical);
    }

    @Test
    void hot_and_cold_against_real_broker_timeline() {
        addSegment(Intervals.of("2024-01-01/2024-01-10"), 0, 1);
        addSegment(Intervals.of("2024-01-15/2024-01-31"), 0, 1);
        inventory.signalInitialized();

        Optional<VersionedIntervalTimeline<String, ServerSelector>> maybe =
                brokerView.getTimeline(new TableDataSource(DATASOURCE).getAnalysis());
        assertTrue(maybe.isPresent());

        VersionedIntervalTimeline<String, ServerSelector> timeline = maybe.get();

        ExecutionMode hot = ExecutionModeSelector.select(
                Collections.singletonList(Intervals.of("2024-01-05/2024-01-20")), timeline);
        assertEquals(ExecutionMode.HOT, hot);

        ExecutionMode cold = ExecutionModeSelector.select(
                Collections.singletonList(Intervals.of("2023-12-31/2024-02-01")), timeline);
        assertEquals(ExecutionMode.COLD, cold);
    }

    @Test
    void multiple_partitions_same_interval() {
        Interval interval = Intervals.of("2024-01-01/2024-01-10");
        addSegment(interval, 0, 3);
        addSegment(interval, 1, 3);
        addSegment(interval, 2, 3);
        inventory.signalInitialized();

        Optional<VersionedIntervalTimeline<String, ServerSelector>> maybe =
                brokerView.getTimeline(new TableDataSource(DATASOURCE).getAnalysis());
        assertTrue(maybe.isPresent());

        VersionedIntervalTimeline<String, ServerSelector> timeline = maybe.get();

        ExecutionMode hot = ExecutionModeSelector.select(
                Collections.singletonList(Intervals.of("2024-01-02/2024-01-09")), timeline);
        assertEquals(ExecutionMode.HOT, hot);

        ExecutionMode cold = ExecutionModeSelector.select(
                Collections.singletonList(Intervals.of("2023-12-31/2024-01-11")), timeline);
        assertEquals(ExecutionMode.COLD, cold);
    }

    private void addSegment(Interval interval, int partitionNumber, int totalPartitions) {
        NumberedShardSpec shard = new NumberedShardSpec(partitionNumber, totalPartitions);
        DataSegment seg = DataSegment.builder()
                .dataSource(ExecutionModeSelectorIT.DATASOURCE)
                .interval(interval)
                .version("v1")
                .size(1L)
                .shardSpec(shard)
                .build();
        inventory.segmentAdded(historical, seg);
    }
}

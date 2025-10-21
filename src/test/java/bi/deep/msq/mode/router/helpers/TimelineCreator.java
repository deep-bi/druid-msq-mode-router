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
package bi.deep.msq.mode.router.helpers;

import java.util.Comparator;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.timeline.partition.ShardSpec;
import org.joda.time.Interval;
import org.mockito.Mockito;

public class TimelineCreator {

    public static VersionedIntervalTimeline<String, ServerSelector> empty() {
        return new VersionedIntervalTimeline<>(Comparator.naturalOrder());
    }

    public static VersionedIntervalTimeline<String, ServerSelector> ofSinglePartition(
            String version, Interval... intervals) {
        VersionedIntervalTimeline<String, ServerSelector> timeline = empty();
        ShardSpec shard = new NumberedShardSpec(0, 1);
        for (Interval interval : intervals) {
            ServerSelector selector = Mockito.mock(ServerSelector.class);
            PartitionChunk<ServerSelector> chunk = shard.createChunk(selector);
            timeline.add(interval, version, chunk);
        }
        return timeline;
    }

    public static VersionedIntervalTimeline<String, ServerSelector> ofNPartitions(
            String version, int partitions, Interval interval) {
        VersionedIntervalTimeline<String, ServerSelector> timeline = empty();
        for (int p = 0; p < partitions; p++) {
            ShardSpec shard = new NumberedShardSpec(p, partitions);
            ServerSelector selector = Mockito.mock(ServerSelector.class);
            PartitionChunk<ServerSelector> chunk = shard.createChunk(selector);
            timeline.add(interval, version, chunk);
        }
        return timeline;
    }
}

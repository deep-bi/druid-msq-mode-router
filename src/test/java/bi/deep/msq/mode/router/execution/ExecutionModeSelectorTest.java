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

import static org.junit.jupiter.api.Assertions.assertEquals;

import bi.deep.msq.mode.router.helpers.TimelineCreator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Stream;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.joda.time.Interval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ExecutionModeSelectorTest {

    @ParameterizedTest(name = "{index}: query={0} -> {1}")
    @MethodSource("singleSpanBoundaryCases")
    void singleSpanBoundary(Interval query, ExecutionMode expected) {
        VersionedIntervalTimeline<String, ServerSelector> timeline =
                TimelineCreator.ofSinglePartition("v1", new Interval("2025-01-01/2025-01-31"));

        ExecutionMode mode = ExecutionModeSelector.select(Collections.singletonList(query), timeline);
        assertEquals(expected, mode);
    }

    static Stream<Object[]> singleSpanBoundaryCases() {
        return Stream.of(
                new Object[] {new Interval("2025-01-01/2025-01-31"), ExecutionMode.HOT},
                new Object[] {new Interval("2025-01-01/2025-01-02"), ExecutionMode.HOT},
                new Object[] {new Interval("2025-01-30/2025-01-31"), ExecutionMode.HOT},
                new Object[] {new Interval("2023-12-31/2025-01-31"), ExecutionMode.COLD},
                new Object[] {new Interval("2025-01-01/2025-02-01"), ExecutionMode.COLD},
                new Object[] {new Interval("2023-12-31T23:59:59.999Z/2025-01-10"), ExecutionMode.COLD},
                new Object[] {new Interval("2025-01-10/2025-02-01T00:00:00.001Z"), ExecutionMode.COLD});
    }

    @ParameterizedTest(name = "{index}: mergedQuery={0} -> {1}")
    @MethodSource("mergedQueryCases")
    void mergedQueryDecisions(Collection<Interval> queryIntervals, ExecutionMode expected) {
        VersionedIntervalTimeline<String, ServerSelector> timeline =
                TimelineCreator.ofSinglePartition("v1", new Interval("2025-01-01/2025-02-01"));

        ExecutionMode mode = ExecutionModeSelector.select((java.util.List<Interval>) queryIntervals, timeline);
        assertEquals(expected, mode);
    }

    static Stream<Object[]> mergedQueryCases() {
        return Stream.of(
                new Object[] {
                    Arrays.asList(
                            new Interval("2025-01-05/2025-01-06"),
                            new Interval("2025-01-10/2025-01-15"),
                            new Interval("2025-01-20/2025-01-25")),
                    ExecutionMode.HOT
                },
                new Object[] {
                    Arrays.asList(new Interval("2024-12-31/2025-01-02"), new Interval("2025-01-20/2025-02-02")),
                    ExecutionMode.COLD
                });
    }

    @ParameterizedTest(name = "{index}: query={0} -> {1}")
    @MethodSource("disjointTimelineCases")
    void disjointTimelineDecisions(Interval query, ExecutionMode expected) {
        VersionedIntervalTimeline<String, ServerSelector> timeline = TimelineCreator.ofSinglePartition(
                "v1", new Interval("2025-01-01/2025-01-10"), new Interval("2025-01-15/2025-01-31"));

        ExecutionMode mode = ExecutionModeSelector.select(Collections.singletonList(query), timeline);
        assertEquals(expected, mode);
    }

    static Stream<Object[]> disjointTimelineCases() {
        return Stream.of(
                new Object[] {new Interval("2025-01-05/2025-01-20"), ExecutionMode.HOT
                }, // missing data on the historical means that there was no data, so we still go to hot
                new Object[] {new Interval("2024-12-31/2025-02-01"), ExecutionMode.COLD});
    }

    @ParameterizedTest(name = "{index}: multi-partitions query={0} -> {1}")
    @MethodSource("multiPartitionCases")
    void multiplePartitionsDecisions(Interval query, ExecutionMode expected) {
        VersionedIntervalTimeline<String, ServerSelector> timeline =
                TimelineCreator.ofNPartitions("v1", 3, new Interval("2025-01-01/2025-01-10"));

        ExecutionMode mode = ExecutionModeSelector.select(Collections.singletonList(query), timeline);
        assertEquals(expected, mode);
    }

    static Stream<Object[]> multiPartitionCases() {
        return Stream.of(
                new Object[] {new Interval("2025-01-02/2025-01-09"), ExecutionMode.HOT},
                new Object[] {new Interval("2024-12-31/2025-01-11"), ExecutionMode.COLD});
    }

    @Test
    void nullTimelineIsCold() {
        ExecutionMode mode =
                ExecutionModeSelector.select(Collections.singletonList(new Interval("2025-01-01/2025-01-02")), null);
        assertEquals(ExecutionMode.COLD, mode);
    }

    @Test
    void emptyTimelineIsCold() {
        VersionedIntervalTimeline<String, ServerSelector> timeline = TimelineCreator.empty();
        ExecutionMode mode = ExecutionModeSelector.select(
                Collections.singletonList(new Interval("2025-01-01/2025-01-02")), timeline);
        assertEquals(ExecutionMode.COLD, mode);
    }

    @Test
    void noIntervalsIsHot() {
        VersionedIntervalTimeline<String, ServerSelector> timeline =
                TimelineCreator.ofSinglePartition("v1", new Interval("2025-01-01/2025-02-01"));
        ExecutionMode mode = ExecutionModeSelector.select(Collections.emptyList(), timeline);
        assertEquals(ExecutionMode.HOT, mode);
    }
}

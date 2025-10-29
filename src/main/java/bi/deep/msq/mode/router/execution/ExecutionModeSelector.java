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

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.druid.client.selector.ServerSelector;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.joda.time.DateTime;
import org.joda.time.Interval;

public class ExecutionModeSelector {

    /*
     * Simple mode selector based on the query interval and the timeline interval
     * In future we may want to consider adding some additional validation based on segment availability on historicals
     * using PartitionHolder which could be taken roughly from timeline.values().stream().flatMap(v -> v.values().stream())
     * */

    public static ExecutionMode select(
            List<Interval> queryIntervals, @Nullable VersionedIntervalTimeline<String, ServerSelector> timeline) {

        if (timeline == null) {
            return ExecutionMode.COLD;
        }

        List<Interval> timelineIntervals =
                new ArrayList<>(timeline.getAllTimelineEntries().keySet());
        Interval timelineInterval = mergeIntervals(timelineIntervals);

        if (timelineInterval == null) {
            return ExecutionMode.COLD;
        }

        Interval queryInterval = mergeIntervals(queryIntervals);

        if (queryInterval == null) { // metadata query?
            return ExecutionMode.HOT;
        }

        if (exceedsBounds(queryInterval, timelineInterval)) {
            return ExecutionMode.COLD;
        } else {
            return ExecutionMode.HOT;
        }
    }

    private static Interval mergeIntervals(List<Interval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return null;
        }

        DateTime start = null;
        DateTime end = null;

        for (Interval interval : intervals) {
            if (start == null || interval.getStart().isBefore(start)) {
                start = interval.getStart();
            }
            if (end == null || interval.getEnd().isAfter(end)) {
                end = interval.getEnd();
            }
        }

        return new Interval(start, end);
    }

    private static boolean exceedsBounds(Interval queryInterval, Interval timelineInterval) {
        if (timelineInterval == null) {
            return true;
        }
        // do we even care about end limit? Seems to be irrelevant for the current use case
        return queryInterval.getStart().isBefore(timelineInterval.getStart())
                || queryInterval.getEnd().isAfter(timelineInterval.getEnd());
    }
}

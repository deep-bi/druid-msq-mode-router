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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.junit.jupiter.api.Test;

class SqlIntervalExtractorTest {

    // ---- extractDataSource ----

    @Test
    void extractsPlainDataSourceName() {
        assertEquals("wikipedia", SqlIntervalExtractor.extractDataSource("SELECT * FROM wikipedia"));
    }

    @Test
    void extractsQuotedDataSourceName() {
        assertEquals("my_table", SqlIntervalExtractor.extractDataSource("SELECT * FROM \"my_table\" WHERE x = 1"));
    }

    @Test
    void returnsNullWhenNoFromClause() {
        assertNull(SqlIntervalExtractor.extractDataSource("SELECT 1"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(SqlIntervalExtractor.extractDataSource(null));
    }

    // ---- extractIntervals: no interval ----

    @Test
    void returnsEmptyListForNoTimeFilter() {
        assertTrue(SqlIntervalExtractor.extractIntervals("SELECT COUNT(*) FROM wikipedia")
                .isEmpty());
    }

    @Test
    void returnsEmptyListForNullSql() {
        assertTrue(SqlIntervalExtractor.extractIntervals(null).isEmpty());
    }

    @Test
    void returnsEmptyListForMalformedTimestamp() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time >= 'not-a-date' AND __time < 'also-not'");
        assertTrue(result.isEmpty());
    }

    // ---- extractIntervals: range operators ----

    @Test
    void extractsIntervalFromGteAndLt() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time >= '2025-01-01' AND __time < '2025-04-01'");
        assertEquals(1, result.size());
        assertInterval(result.get(0), 2025, 1, 1, 2025, 4, 1);
    }

    @Test
    void extractsIntervalFromGtAndLte() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time > '2024-06-01' AND __time <= '2024-12-31'");
        assertEquals(1, result.size());
    }

    @Test
    void extractsIntervalWithTimestampKeyword() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time >= TIMESTAMP '2025-01-01 00:00:00' AND __time < TIMESTAMP '2025-02-01 00:00:00'");
        assertEquals(1, result.size());
        assertInterval(result.get(0), 2025, 1, 1, 2025, 2, 1);
    }

    @Test
    void extractsIntervalWithSqlStyleSpaceTimestamp() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time >= '2025-03-01 00:00:00' AND __time < '2025-03-15 00:00:00'");
        assertEquals(1, result.size());
        assertInterval(result.get(0), 2025, 3, 1, 2025, 3, 15);
    }

    // ---- extractIntervals: BETWEEN ----

    @Test
    void extractsIntervalFromBetween() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time BETWEEN '2025-01-01' AND '2025-06-30'");
        assertEquals(1, result.size());
        assertInterval(result.get(0), 2025, 1, 1, 2025, 6, 30);
    }

    // ---- extractIntervals: TIME_IN_INTERVAL ----

    @Test
    void extractsIntervalFromTimeInInterval() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE TIME_IN_INTERVAL(__time, '2015-09-12/2015-09-13')");
        assertEquals(1, result.size());
        assertInterval(result.get(0), 2015, 9, 12, 2015, 9, 13);
    }

    @Test
    void extractsIntervalFromTimeInIntervalWithSpaces() {
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE TIME_IN_INTERVAL( __time , \"2015-01-01/2016-01-01\" )");
        assertEquals(1, result.size());
    }

    // ---- edge cases ----

    @Test
    void skipsInvertedInterval() {
        // end < start should be dropped
        List<Interval> result = SqlIntervalExtractor.extractIntervals(
                "SELECT * FROM t WHERE __time >= '2025-06-01' AND __time < '2025-01-01'");
        assertTrue(result.isEmpty());
    }

    // ---- helpers ----

    private static void assertInterval(Interval iv, int sy, int sm, int sd, int ey, int em, int ed) {
        assertEquals(new DateTime(sy, sm, sd, 0, 0, DateTimeZone.UTC), iv.getStart());
        assertEquals(new DateTime(ey, em, ed, 0, 0, DateTimeZone.UTC), iv.getEnd());
    }
}

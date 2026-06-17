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
import org.joda.time.Interval;
import org.junit.jupiter.api.Test;

class SqlIntervalExtractorTest {

    // ---- extractDataSource ----

    @Test
    void extractsPlainDataSourceName() {
        assertEquals(
                "wikipedia",
                extractDataSource("SELECT * FROM wikipedia WHERE __time >= '2025-01-01' AND __time < '2025-04-01'"));
    }

    @Test
    void extractsQuotedDataSourceName() {
        assertEquals(
                "my_table",
                extractDataSource(
                        "SELECT * FROM \"my_table\" WHERE __time >= '2025-01-01' AND __time < '2025-04-01' AND x = 1"));
    }

    @Test
    void extractsQuotedDataSourceNameWithHyphen() {
        assertEquals(
                "wikipedia-raw",
                extractDataSource(
                        "SELECT * FROM \"wikipedia-raw\" WHERE __time >= '2025-01-01' AND __time < '2025-04-01'"));
    }

    @Test
    void returnsNullWhenNoFromClause() {
        assertNull(extractDataSource("SELECT 1"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(extractDataSource(null));
    }

    // ---- extractIntervals: no interval ----

    @Test
    void returnsEmptyListForNoTimeFilter() {
        assertTrue(extractIntervals("SELECT COUNT(*) FROM wikipedia").isEmpty());
    }

    @Test
    void returnsEmptyListForNullSql() {
        assertTrue(extractIntervals(null).isEmpty());
    }

    @Test
    void returnsEmptyListForMalformedTimestamp() {
        List<Interval> result =
                extractIntervals("SELECT * FROM t WHERE __time >= 'not-a-date' AND __time < 'also-not'");
        assertTrue(result.isEmpty());
    }

    // ---- extractIntervals: range operators ----

    @Test
    void extractsIntervalFromGteAndLt() {
        List<Interval> result =
                extractIntervals("SELECT * FROM t WHERE __time >= '2025-01-01' AND __time < '2025-04-01'");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-01-01"), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-04-01").minusMillis(1), result.get(0).getEnd());
    }

    @Test
    void extractsIntervalFromGtAndLte() {
        List<Interval> result =
                extractIntervals("SELECT * FROM t WHERE __time > '2024-06-01' AND __time <= '2024-12-31'");
        assertEquals(1, result.size());
    }

    @Test
    void extractsIntervalWithTimestampKeyword() {
        List<Interval> result = extractIntervals(
                "SELECT * FROM t WHERE __time > TIMESTAMP '2025-01-01 00:00:00' AND __time <= TIMESTAMP '2025-02-01 00:00:00'");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-01-01").plusMillis(1), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-02-01"), result.get(0).getEnd());
    }

    @Test
    void extractsIntervalWithSqlStyleSpaceTimestamp() {
        List<Interval> result = extractIntervals(
                "SELECT * FROM t WHERE __time >= '2025-03-01 00:00:00' AND __time <= '2025-03-15 00:00:00'");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-03-01"), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-03-15"), result.get(0).getEnd());
    }

    // ---- extractIntervals: BETWEEN ----

    @Test
    void extractsIntervalFromBetween() {
        List<Interval> result = extractIntervals("SELECT * FROM t WHERE __time BETWEEN '2025-01-01' AND '2025-06-30'");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-01-01"), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-06-30"), result.get(0).getEnd());
    }

    // ---- extractIntervals: TIME_IN_INTERVAL ----

    @Test
    void extractsIntervalFromTimeInInterval() {
        List<Interval> result =
                extractIntervals("SELECT * FROM t WHERE TIME_IN_INTERVAL(__time, '2015-09-12/2015-09-13')");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2015-09-12"), result.get(0).getStart());
        assertEquals(DateTime.parse("2015-09-13"), result.get(0).getEnd());
    }

    @Test
    void extractsIntervalFromTimeInIntervalWithSpaces() {
        List<Interval> result =
                extractIntervals("SELECT * FROM t WHERE TIME_IN_INTERVAL( __time , \"2015-01-01/2016-01-01\" )");
        assertEquals(1, result.size());
    }

    // ---- extractIntervals: CTEs (WITH clause) ----

    @Test
    void extractsIntervalFromCteOuterSelect() {
        List<Interval> result = extractIntervals("WITH recent AS (SELECT * FROM t) SELECT * FROM recent"
                + " WHERE __time >= '2025-01-01' AND __time <= '2025-04-01'");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-01-01"), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-04-01"), result.get(0).getEnd());
    }

    @Test
    void extractsIntervalFromCteWithOrderBy() {
        List<Interval> result = extractIntervals("WITH recent AS (SELECT * FROM t) SELECT * FROM recent"
                + " WHERE __time >= '2025-01-01' AND __time < '2025-04-01' ORDER BY __time DESC");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-01-01"), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-04-01").minusMillis(1), result.get(0).getEnd());
    }

    @Test
    void cteDataSourceIsAliasNotUnderlyingTable() {
        // The outer SELECT references the CTE alias - datasource extraction returns that alias.
        // getTimeline("recent") will return empty, so routing falls to COLD, which is the safe default.
        assertEquals(
                "recent",
                extractDataSource(
                        "WITH recent AS (SELECT * FROM wikipedia) SELECT * FROM recent WHERE __time >= '2025-01-01'"));
    }

    // ---- edge cases ----

    @Test
    void extractsIntervalFromQueryWithOrderBy() {
        List<Interval> result = extractIntervals(
                "SELECT * FROM t WHERE __time >= '2025-01-01' AND __time <= '2025-04-01' ORDER BY __time DESC");
        assertEquals(1, result.size());
        assertEquals(DateTime.parse("2025-01-01"), result.get(0).getStart());
        assertEquals(DateTime.parse("2025-04-01"), result.get(0).getEnd());
    }

    @Test
    void extractsDataSourceFromQueryWithOrderBy() {
        assertEquals(
                "wikipedia",
                extractDataSource(
                        "SELECT * FROM wikipedia WHERE __time >= '2025-01-01' AND __time < '2025-04-01' AND x = 1 ORDER BY x"));
    }

    @Test
    void skipsInvertedInterval() {
        // end < start should be dropped
        List<Interval> result =
                extractIntervals("SELECT * FROM t WHERE __time >= '2025-06-01' AND __time < '2025-01-01'");
        assertTrue(result.isEmpty());
    }

    // ---- helpers ----

    private static String extractDataSource(String sql) {
        return SqlIntervalExtractor.extractWithInterval(sql).dataSource;
    }

    private static List<Interval> extractIntervals(String sql) {
        return SqlIntervalExtractor.extractWithInterval(sql).intervals;
    }
}

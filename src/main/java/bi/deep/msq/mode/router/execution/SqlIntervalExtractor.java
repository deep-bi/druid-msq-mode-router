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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.format.ISODateTimeFormat;

/**
 * Lightweight regex-based extractor for datasource and {@code __time} intervals from Druid SQL.
 *
 * <p>Handles three patterns:
 * <ul>
 *   <li>{@code TIME_IN_INTERVAL(__time, 'start/end')}</li>
 *   <li>{@code __time >= 'X' AND __time < 'Y'} (operators {@code >}, {@code >=}, {@code <}, {@code <=})</li>
 *   <li>{@code __time BETWEEN 'X' AND 'Y'}</li>
 * </ul>
 * Returns an empty list when no parseable interval is found; callers treat that as "no time filter,
 * default to COLD" to ensure complete results across all history.
 */
public class SqlIntervalExtractor {

    // FROM <identifier> — handles bare names and quoted identifiers
    private static final Pattern DATASOURCE_PATTERN = Pattern.compile("(?i)\\bFROM\\s+[\"'`]?(\\w+)[\"'`]?");

    // TIME_IN_INTERVAL(__time, 'start/end')
    private static final Pattern TIME_IN_INTERVAL_PATTERN =
            Pattern.compile("(?i)TIME_IN_INTERVAL\\s*\\(\\s*__time\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    // __time >= 'X'  or  __time > 'X'  (lower bound)
    private static final Pattern LOWER_BOUND_PATTERN =
            Pattern.compile("(?i)__time\\s*>=?\\s*(?:TIMESTAMP\\s+)?['\"]([^'\"]+)['\"]");

    // __time <= 'X'  or  __time < 'X'  (upper bound)
    private static final Pattern UPPER_BOUND_PATTERN =
            Pattern.compile("(?i)__time\\s*<=?\\s*(?:TIMESTAMP\\s+)?['\"]([^'\"]+)['\"]");

    // __time BETWEEN 'X' AND 'Y'
    private static final Pattern BETWEEN_PATTERN =
            Pattern.compile("(?i)__time\\s+BETWEEN\\s+['\"]([^'\"]+)['\"]\\s+AND\\s+['\"]([^'\"]+)['\"]");

    private SqlIntervalExtractor() {}

    @Nullable
    public static String extractDataSource(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher m = DATASOURCE_PATTERN.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    public static List<Interval> extractIntervals(String sql) {
        List<Interval> result = new ArrayList<>();
        if (sql == null) {
            return result;
        }

        // TIME_IN_INTERVAL(__time, 'start/end')
        Matcher tim = TIME_IN_INTERVAL_PATTERN.matcher(sql);
        while (tim.find()) {
            String spec = tim.group(1);
            int slash = spec.indexOf('/');
            if (slash > 0) {
                Interval iv = toInterval(
                        spec.substring(0, slash).trim(),
                        spec.substring(slash + 1).trim());
                if (iv != null) {
                    result.add(iv);
                }
            }
        }

        // __time BETWEEN 'X' AND 'Y'
        Matcher between = BETWEEN_PATTERN.matcher(sql);
        while (between.find()) {
            Interval iv = toInterval(between.group(1), between.group(2));
            if (iv != null) {
                result.add(iv);
            }
        }

        // __time >= 'X' ... __time < 'Y'  (range operators)
        Matcher lower = LOWER_BOUND_PATTERN.matcher(sql);
        Matcher upper = UPPER_BOUND_PATTERN.matcher(sql);
        if (lower.find() && upper.find()) {
            Interval iv = toInterval(lower.group(1), upper.group(1));
            if (iv != null) {
                result.add(iv);
            }
        }

        return result;
    }

    @Nullable
    private static Interval toInterval(String startStr, String endStr) {
        try {
            DateTime start = parseTimestamp(startStr);
            DateTime end = parseTimestamp(endStr);
            if (start != null && end != null && start.isBefore(end)) {
                return new Interval(start, end);
            }
        } catch (Exception ignored) {
            // malformed timestamp → skip
        }
        return null;
    }

    @Nullable
    private static DateTime parseTimestamp(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            // Normalise SQL-style "2025-01-01 00:00:00" to ISO "2025-01-01T00:00:00"
            String iso = s.trim().replace(" ", "T");
            return ISODateTimeFormat.dateTimeParser().withZone(DateTimeZone.UTC).parseDateTime(iso);
        } catch (Exception e) {
            return null;
        }
    }
}

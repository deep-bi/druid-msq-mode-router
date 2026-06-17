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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.NlsString;
import org.apache.calcite.util.TimestampString;
import org.apache.druid.sql.calcite.parser.DruidSqlParserImplFactory;
import org.apache.druid.sql.calcite.planner.DruidConformance;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.format.ISODateTimeFormat;

public class SqlIntervalExtractor {

    public static final class Result {
        public final String dataSource;
        public final List<Interval> intervals;

        private Result(String dataSource, List<Interval> intervals) {
            this.dataSource = dataSource;
            this.intervals = intervals;
        }

        public static final Result EMPTY = new Result(null, Collections.emptyList());
    }

    private SqlIntervalExtractor() {}

    // Druid-compatible parser config: case-sensitive identifiers, double-quote quoting, DruidConformance dialect.
    private static final SqlParser.Config PARSER_CONFIG = SqlParser.config()
            .withCaseSensitive(true)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withConformance(DruidConformance.instance())
            .withParserFactory(new DruidSqlParserImplFactory());

    /**
     * Parses {@code sql} once and returns both the datasource and any {@code __time} intervals.
     * Returns {@link Result#EMPTY} on null input or any parse/walk error.
     */
    public static Result extract(String sql) {
        if (sql == null) {
            return Result.EMPTY;
        }
        try {
            SqlNode stmt = SqlParser.create(sql, PARSER_CONFIG).parseQuery();

            // ORDER BY wraps the SELECT in SqlOrderBy — unwrap to reach the SqlSelect
            if (stmt instanceof SqlOrderBy) {
                stmt = ((SqlOrderBy) stmt).query;
            }

            // CTEs wrap the body SELECT in SqlWith
            if (stmt instanceof SqlWith) {
                stmt = ((SqlWith) stmt).body;
            }

            if (!(stmt instanceof SqlSelect)) {
                return Result.EMPTY;
            }

            SqlSelect select = (SqlSelect) stmt;
            String dataSource = extractTableName(select.getFrom());

            return Optional.ofNullable(select.getWhere())
                    .map(SqlIntervalExtractor::extractIntervalsFromWhere)
                    .map(intervals -> new Result(dataSource, intervals))
                    .orElseGet(() -> new Result(dataSource, Collections.emptyList()));
        } catch (SqlParseException | RuntimeException e) {
            return Result.EMPTY;
        }
    }

    @Nullable
    private static String extractTableName(SqlNode from) {
        if (from == null) {
            return null;
        }
        if (from instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) from;
            // For dotted names (schema.table) take the last component
            return id.isSimple() ? id.getSimple() : id.names.get(id.names.size() - 1);
        }
        if (from instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) from;
            if (call.getKind() == SqlKind.AS) {
                return extractTableName(call.operand(0));
            }
        }
        return null;
    }

    private static List<Interval> extractIntervalsFromWhere(SqlNode where) {
        BoundCollector collector = new BoundCollector();
        collectBounds(where, collector);
        if (collector.lower != null && collector.upper != null) {
            Interval iv = toInterval(collector.lower, collector.upper);
            if (iv != null) {
                collector.completeIntervals.add(iv);
            }
        }
        return collector.completeIntervals.isEmpty() ? Collections.emptyList() : collector.completeIntervals;
    }

    private static final class BoundCollector {
        DateTime lower;
        DateTime upper;
        final List<Interval> completeIntervals = new ArrayList<>();
    }

    private static void collectBounds(SqlNode node, BoundCollector collector) {
        if (!(node instanceof SqlBasicCall)) {
            return;
        }
        SqlBasicCall call = (SqlBasicCall) node;
        switch (call.getKind()) {
            case AND:
                for (SqlNode operand : call.getOperandList()) {
                    collectBounds(operand, collector);
                }
                break;

            case BETWEEN:
                // operands: [value, lower, upper]
                if (call.operandCount() == 3 && isTimeColumn(call.operand(0))) {
                    DateTime start = parseLiteral(call.operand(1));
                    DateTime end = parseLiteral(call.operand(2));
                    Interval iv = toInterval(start, end);
                    if (iv != null) {
                        collector.completeIntervals.add(iv);
                    }
                }
                break;

            case GREATER_THAN_OR_EQUAL:
            case GREATER_THAN:
                if (collector.lower == null && isTimeColumn(call.operand(0))) {
                    collector.lower = parseLiteral(call.operand(1));
                }
                break;

            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
                if (collector.upper == null && isTimeColumn(call.operand(0))) {
                    collector.upper = parseLiteral(call.operand(1));
                }
                break;

            case OTHER_FUNCTION:
                // TIME_IN_INTERVAL(__time, 'start/end') or TIME_IN_INTERVAL(__time, "start/end")
                if ("TIME_IN_INTERVAL".equalsIgnoreCase(call.getOperator().getName())
                        && call.operandCount() == 2
                        && isTimeColumn(call.operand(0))) {
                    String spec = intervalSpec(call.operand(1));
                    if (spec != null) {
                        int slash = spec.indexOf('/');
                        if (slash > 0) {
                            DateTime start =
                                    parseTimestamp(spec.substring(0, slash).trim());
                            DateTime end =
                                    parseTimestamp(spec.substring(slash + 1).trim());
                            Interval iv = toInterval(start, end);
                            if (iv != null) {
                                collector.completeIntervals.add(iv);
                            }
                        }
                    }
                }
                break;

            default:
                break;
        }
    }

    private static boolean isTimeColumn(SqlNode node) {
        return node instanceof SqlIdentifier
                && ((SqlIdentifier) node).isSimple()
                && "__time".equals(((SqlIdentifier) node).getSimple());
    }

    @Nullable
    private static DateTime parseLiteral(SqlNode node) {
        if (node instanceof SqlLiteral) {
            SqlLiteral lit = (SqlLiteral) node;
            Object val = lit.getValue();
            String s;
            if (val instanceof NlsString) {
                // 'YYYY-MM-DD' or 'YYYY-MM-DD HH:mm:ss' string literal
                s = ((NlsString) val).getValue();
            } else if (val instanceof TimestampString) {
                // resolved TIMESTAMP literal
                s = val.toString();
            } else if (val instanceof String) {
                // SqlUnknownLiteral: TIMESTAMP '...' before validation has a plain String value
                s = (String) val;
            } else {
                return null;
            }
            return parseTimestamp(s);
        }
        if (node instanceof SqlBasicCall) {
            SqlBasicCall call = (SqlBasicCall) node;
            // TIMESTAMP '...' can be parsed as CAST or as a function call depending on conformance
            if ((call.getKind() == SqlKind.CAST || call.getKind() == SqlKind.OTHER_FUNCTION)
                    && call.operandCount() >= 1) {
                return parseLiteral(call.operand(0));
            }
        }
        return null;
    }

    @Nullable
    private static String intervalSpec(SqlNode node) {
        if (node instanceof SqlLiteral) {
            SqlLiteral lit = (SqlLiteral) node;
            if (SqlTypeName.CHAR_TYPES.contains(lit.getTypeName())) {
                return ((NlsString) lit.getValue()).getValue();
            }
        }
        // double-quoted form is parsed as an identifier: "2015-01-01/2016-01-01"
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            if (id.isSimple()) {
                return id.getSimple();
            }
        }
        return null;
    }

    @Nullable
    private static Interval toInterval(DateTime start, DateTime end) {
        if (start != null && end != null && start.isBefore(end)) {
            return new Interval(start, end);
        }
        return null;
    }

    @Nullable
    private static DateTime parseTimestamp(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            // Normalize SQL-style "2025-01-01 00:00:00" to ISO "2025-01-01T00:00:00"
            return ISODateTimeFormat.dateTimeParser()
                    .withZone(DateTimeZone.UTC)
                    .parseDateTime(s.trim().replace(" ", "T"));
        } catch (Exception e) {
            return null;
        }
    }
}

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
package bi.deep.msq.mode.router.util;

import bi.deep.msq.mode.router.execution.ResultsDecorationStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.segment.column.ColumnType;

public class ResultsDecorator {
    private static final Logger LOGGER = new Logger(ResultsDecorator.class);

    public static byte[] decorate(
            final ObjectMapper mapper, final byte[] rawResults, final ResultsDecorationStrategy strategy)
            throws IOException {
        if (strategy == ResultsDecorationStrategy.NONE) {
            LOGGER.debug("Decoration disabled.");
            return rawResults;
        }

        final ArrayNode rows = readArray(mapper, rawResults);
        switch (strategy) {
            case ORDERED_SCAN:
                return decorateOrderedScan(mapper, rows);
            case GROUP_BY:
                return decorateGroupBy(mapper, rows);
            default:
                throw new IOException("Unsupported strategy: " + strategy);
        }
    }

    private static byte[] decorateGroupBy(final ObjectMapper mapper, final ArrayNode rows) throws IOException {
        final ArrayNode out = mapper.createArrayNode();

        for (JsonNode rowNode : rows) {
            if (!rowNode.isObject()) {
                throw new IOException("Expected object for groupBy row, got: " + rowNode.getNodeType());
            }
            final ObjectNode wrapped = mapper.createObjectNode();

            wrapped.putNull("version");
            wrapped.putNull("timestamp");
            wrapped.set("event", rowNode.deepCopy()); // keep raw event as-is

            out.add(wrapped);
        }
        return mapper.writeValueAsBytes(out);
    }

    // supposed to work with compactedList
    private static byte[] decorateOrderedScan(final ObjectMapper mapper, final ArrayNode rows) throws IOException {

        if (rows.isEmpty()) {
            return mapper.writeValueAsBytes(mapper.createArrayNode());
        }

        final List<String> columns = collectColumns(rows);

        moveTimeFirst(columns); // to be sure it's aligns with druid internal ordering, probably it's already first

        final ArrayNode events = mapper.createArrayNode();

        for (JsonNode rowNode : rows) {
            if (!rowNode.isObject()) {
                throw new IOException("Expected object for scan row, got: " + rowNode.getNodeType());
            }
            final ObjectNode obj = (ObjectNode) rowNode;
            final ArrayNode vals = mapper.createArrayNode();
            for (String c : columns) {
                final JsonNode v = obj.get(c);
                if (v == null || v.isNull()) {
                    vals.add(NullNode.getInstance());
                } else if (v.isIntegralNumber()) {
                    vals.add(v.longValue());
                } else if (v.isFloatingPointNumber()) {
                    vals.add(v.doubleValue());
                } else if (v.isTextual()) {
                    vals.add(v.textValue());
                } else if (v.isBoolean()) {
                    // treat as STRING in rowSignature for widest compatibility
                    vals.add(v.booleanValue());
                } else {
                    // Fallback to string
                    vals.add(v.asText());
                }
            }
            events.add(vals);
        }

        final ArrayNode rowSignature = buildRowSignatureJson(mapper, inferTypes(rows, columns));

        final ObjectNode envelope = mapper.createObjectNode();
        envelope.putNull("segmentId");
        envelope.set("columns", toArray(mapper, columns));
        envelope.set("events", events);
        envelope.set("rowSignature", rowSignature);

        final ArrayNode out = mapper.createArrayNode();
        out.add(envelope);
        return mapper.writeValueAsBytes(out);
    }

    private static Map<String, ColumnType> inferTypes(final ArrayNode rows, final List<String> columns) {
        final Map<String, ColumnType> types = new LinkedHashMap<>(Math.max(16, columns.size() * 2));
        for (String c : columns) {
            boolean seenDouble = false;
            boolean seenLong = false;
            boolean seenOther = false;

            for (JsonNode r : rows) {
                final JsonNode v = r.get(c);
                if (v == null || v.isNull()) continue;

                if (v.isFloatingPointNumber()) {
                    seenDouble = true;
                } else if (v.isIntegralNumber()) {
                    seenLong = true;
                } else {
                    seenOther = true;
                    break; // fallback to STRING
                }
            }

            final ColumnType chosen = seenOther
                    ? ColumnType.STRING
                    : (seenDouble ? ColumnType.DOUBLE : (seenLong ? ColumnType.LONG : ColumnType.STRING));

            types.put(c, chosen);
        }
        return types;
    }

    private static ArrayNode readArray(final ObjectMapper mapper, final byte[] raw) throws IOException {
        final JsonNode tree = mapper.readTree(raw);
        if (tree == null || !tree.isArray()) {
            throw new IOException("Expected JSON array of rows");
        }
        for (JsonNode n : tree) {
            if (!n.isObject()) {
                throw new IOException("Expected each row to be a JSON object");
            }
        }
        return (ArrayNode) tree;
    }

    private static ArrayNode toArray(final ObjectMapper mapper, final List<String> cols) {
        final ArrayNode arr = mapper.createArrayNode();
        cols.forEach(arr::add);
        return arr;
    }

    private static List<String> collectColumns(final ArrayNode rows) {
        final Set<String> ordered = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            row.fieldNames().forEachRemaining(ordered::add);
        }
        return new ArrayList<>(ordered);
    }

    private static void moveTimeFirst(final List<String> columns) {
        final int idx = columns.indexOf("__time");
        if (idx > 0) {
            columns.remove(idx);
            columns.add(0, "__time");
        }
    }

    private static ArrayNode buildRowSignatureJson(final ObjectMapper mapper, final Map<String, ColumnType> types) {
        final ArrayNode arr = mapper.createArrayNode();
        for (Map.Entry<String, ColumnType> e : types.entrySet()) {
            final ObjectNode sig = mapper.createObjectNode();
            sig.put("name", e.getKey());
            sig.put("type", e.getValue().toString());
            arr.add(sig);
        }
        return arr;
    }
}

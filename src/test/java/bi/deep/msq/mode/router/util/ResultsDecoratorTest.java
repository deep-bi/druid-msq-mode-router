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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bi.deep.msq.mode.router.execution.ResultsDecorationStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ResultsDecoratorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INITIAL_SCAN =
            "[{\"__time\":1758326400000,\"value\":\"20\"},{\"__time\":1758412800000,\"value\":\"21\"}]";

    private static final String DECORATED_SCAN = "[{\"segmentId\":null,"
            + "\"columns\":[\"__time\",\"value\"],"
            + "\"events\":[[1758326400000,\"20\"],[1758412800000,\"21\"]],"
            + "\"rowSignature\":[{\"name\":\"__time\",\"type\":\"LONG\"},{\"name\":\"value\",\"type\":\"STRING\"}]}]";

    private static final String MULTITYPE_INPUT =
            "[{\"value\":\"10\",\"__time\":1},{\"__time\":2,\"floatCol\":2.5},{\"__time\":3,\"boolCol\":true}]";

    private static final String MULTITYPE_OUTPUT = "[{\"segmentId\":null,"
            + "\"columns\":[\"__time\",\"value\",\"floatCol\",\"boolCol\"],"
            + "\"events\":[[1,\"10\",null,null],[2,null,2.5,null],[3,null,null,true]],"
            + "\"rowSignature\":["
            + "{\"name\":\"__time\",\"type\":\"LONG\"},"
            + "{\"name\":\"value\",\"type\":\"STRING\"},"
            + "{\"name\":\"floatCol\",\"type\":\"DOUBLE\"},"
            + "{\"name\":\"boolCol\",\"type\":\"STRING\"}"
            + "]}]";

    private static final String EMPTY_INPUT = "[]";
    private static final String INVALID_INPUT = "[1]";
    private static final String GROUPBY_INPUT = "[{\"sum_value\":275.0,\"rows\":11}]";

    private static final String GROUPBY_OUTPUT =
            "[{\"version\":null,\"timestamp\":null,\"event\":{\"sum_value\":275.0,\"rows\":11}}]";

    private static byte[] getBytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode parse(final byte[] b) throws IOException {
        return MAPPER.readTree(b);
    }

    @Test
    void orderedScanBasic() throws Exception {
        final byte[] out =
                ResultsDecorator.decorate(MAPPER, getBytes(INITIAL_SCAN), ResultsDecorationStrategy.ORDERED_SCAN);
        final JsonNode root = parse(out);
        assertTrue(root.isArray());
        assertEquals(1, root.size());
        assertEquals(DECORATED_SCAN, root.toString());
    }

    @Test
    void orderedScanInfersTypesAndMovesTimeFirst() throws Exception {
        final byte[] out =
                ResultsDecorator.decorate(MAPPER, getBytes(MULTITYPE_INPUT), ResultsDecorationStrategy.ORDERED_SCAN);
        assertEquals(MULTITYPE_OUTPUT, new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void orderedScanEmptyRowsReturnsEmptyArray() throws Exception {
        final byte[] out =
                ResultsDecorator.decorate(MAPPER, getBytes(EMPTY_INPUT), ResultsDecorationStrategy.ORDERED_SCAN);
        final JsonNode root = parse(out);
        assertTrue(root.isArray());
        assertEquals(0, root.size());
        assertEquals("[]", root.toString());
    }

    @Test
    void orderedScanThrowsOnInvalidRow() {
        assertThrows(
                IOException.class,
                () -> ResultsDecorator.decorate(
                        MAPPER, getBytes(INVALID_INPUT), ResultsDecorationStrategy.ORDERED_SCAN));
    }

    @Test
    void groupByWrapsRows() throws Exception {
        final byte[] out =
                ResultsDecorator.decorate(MAPPER, getBytes(GROUPBY_INPUT), ResultsDecorationStrategy.GROUP_BY);

        assertEquals(GROUPBY_OUTPUT, new String(out, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDERED_SCAN", "GROUP_BY"})
    void decorateThrowsOnNonArray(final String strategyName) {
        final byte[] notArray = "{}".getBytes(StandardCharsets.UTF_8);
        final ResultsDecorationStrategy strategy = ResultsDecorationStrategy.valueOf(strategyName);
        assertThrows(IOException.class, () -> ResultsDecorator.decorate(MAPPER, notArray, strategy));
    }
}

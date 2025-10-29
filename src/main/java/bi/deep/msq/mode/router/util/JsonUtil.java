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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public final class JsonUtil {

    public static String jsonStringField(final ObjectMapper mapper, final byte[] json, final String field)
            throws IOException {
        if (mapper == null || json == null || field == null)
            throw new IllegalArgumentException("mapper, json, and field must be non-null");

        try (JsonParser parser = mapper.getFactory().createParser(json, 0, json.length)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.FIELD_NAME && field.equals(parser.getCurrentName())) {
                    JsonToken value = parser.nextToken();
                    if (value == JsonToken.VALUE_NULL) return null;
                    return parser.getValueAsString(null);
                }
            }
            return null;
        }
    }
}

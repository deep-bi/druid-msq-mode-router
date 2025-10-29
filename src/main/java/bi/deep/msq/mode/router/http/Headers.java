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
package bi.deep.msq.mode.router.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.servlet.http.HttpServletRequest;
import org.apache.druid.java.util.http.client.Request;

public final class Headers {

    private static final Set<String> ENTITY_EXCLUDES = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        ENTITY_EXCLUDES.add("Content-Type");
        ENTITY_EXCLUDES.add("Content-Length");
        ENTITY_EXCLUDES.add("Content-Encoding");
        ENTITY_EXCLUDES.add("Content-MD5");
        ENTITY_EXCLUDES.add("Expect");
    }

    private final NavigableMap<String, List<String>> headersByName; // case-insensitive

    private Headers(final NavigableMap<String, List<String>> headers) {
        this.headersByName = Collections.unmodifiableNavigableMap(headers);
    }

    public static Headers snapshot(final HttpServletRequest in) {
        return newBuilder().copyFrom(in).build();
    }

    public Map<String, List<String>> view() {
        return filterExcluding(headersByName);
    }

    public void applyTo(final Request request) {
        apply(request, view());
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private final NavigableMap<String, List<String>> acc = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public Builder copyFrom(final HttpServletRequest in) {
            final Enumeration<String> names = in.getHeaderNames();
            while (names.hasMoreElements()) {
                final String name = names.nextElement();
                final List<String> raw = Collections.list(in.getHeaders(name));
                final List<String> clean = new ArrayList<>(raw.size());
                for (final String v : raw) {
                    if (v != null)
                        clean.add(v.replace("\r", "").replace("\n", "").trim());
                }
                if (!clean.isEmpty()) acc.put(name, clean);
            }
            return this;
        }

        public Builder add(final String name, final String value) {
            acc.compute(name, (k, vs) -> {
                final List<String> out = (vs == null) ? new ArrayList<>() : new ArrayList<>(vs);
                out.add(value);
                return out;
            });
            return this;
        }

        public Headers build() {
            return new Headers(new TreeMap<>(acc));
        }
    }

    private static void apply(final Request request, final Map<String, List<String>> headers) {
        final Collection<String> existingNames = request.getHeaders().keySet();

        for (final Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (containsHeaderIgnoreCase(existingNames, e.getKey())) continue;

            final List<String> values = e.getValue();
            if (values != null && !values.isEmpty()) {
                request.addHeaderValues(e.getKey(), values);
            }
        }
    }

    private static boolean containsHeaderIgnoreCase(final Collection<String> names, final String name) {
        for (final String n : names) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static Map<String, List<String>> filterExcluding(final Map<String, List<String>> src) {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> e : src.entrySet()) {
            if (Headers.ENTITY_EXCLUDES.contains(e.getKey())) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}

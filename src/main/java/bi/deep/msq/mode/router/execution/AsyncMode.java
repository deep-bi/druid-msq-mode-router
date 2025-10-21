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

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.Duration;

public class AsyncMode {
    private final String defaultMode;

    private final Map<String, Optional<Duration>> modes;

    public AsyncMode(String defaultMode) {
        this.defaultMode = defaultMode;

        this.modes = ImmutableMap.<String, Optional<Duration>>builder()
                .put("async", Optional.empty())
                .put("sync", Optional.of(Duration.standardDays(1)))
                .build();
    }

    public Optional<Duration> parse(@Nullable String mode) {
        if (mode == null || mode.isEmpty()) {
            return modes.get(defaultMode);
        }
        return Optional.ofNullable(modes.get(mode))
                .orElseThrow(() -> new IllegalArgumentException("Unknown mode: " + mode));
    }
}

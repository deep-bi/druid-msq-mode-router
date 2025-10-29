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
package bi.deep.msq.mode.router.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.joda.time.Duration;

public final class TimeUtil {

    public static long deadlineNs(final Duration patience) {
        return System.nanoTime() + patience.getMillis() * 1_000_000L;
    }

    public static long remainingMillis(final long deadlineNanos) throws TimeoutException {
        long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (left <= 0) {
            throw new TimeoutException("Deadline exceeded");
        }
        return left;
    }

    public static long secondsToMillis(final long seconds) {
        return TimeUnit.SECONDS.toMillis(seconds);
    }
}

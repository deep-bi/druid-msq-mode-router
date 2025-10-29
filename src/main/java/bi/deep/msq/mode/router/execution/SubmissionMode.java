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

public enum SubmissionMode {
    ASYNC,
    SYNC;

    public static SubmissionMode fromString(String mode) {
        if (mode == null || mode.isEmpty()) {
            return SYNC;
        }
        for (SubmissionMode result : values()) {
            if (result.name().equalsIgnoreCase(mode)) {
                return result;
            }
        }
        throw new IllegalArgumentException("Unknown mode: " + mode);
    }
}

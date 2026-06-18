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

public class ApiPaths {

    public static final String ROUTER_BASE = "/druid-ext/query-router";
    public static final String DRUID_BASE = "/druid";
    public static final String V2 = "/v2";
    public static final String ROUTER_V2 = ROUTER_BASE + V2;
    public static final String DRUID_V2 = DRUID_BASE + V2;
    public static final String MSQ_QUERY = DRUID_V2 + "/native/statements";
    public static final String SQL_QUERY = DRUID_V2 + "/sql";
    public static final String SQL_MSQ_QUERY = DRUID_V2 + "/sql/statements";
}

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

import bi.deep.msq.mode.router.http.ApiPaths;
import bi.deep.msq.mode.router.http.HttpRequestFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import javax.servlet.http.HttpServletRequest;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;

public class HotQueryExecutor extends BaseQueryExecutor {

    public HotQueryExecutor(ObjectMapper jsonMapper, HttpClient httpClient) {
        super(jsonMapper, httpClient);
    }

    @Override
    protected Request buildRequest(URI base, byte[] payload, HttpServletRequest httpRequest) throws IOException {
        URL url = base.resolve(ApiPaths.DRUID_V2 + "/").toURL();
        return HttpRequestFactory.buildInternalRequest(url, payload, httpRequest);
    }
}

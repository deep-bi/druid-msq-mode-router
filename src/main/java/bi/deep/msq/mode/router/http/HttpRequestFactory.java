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
package bi.deep.msq.mode.router.http;

import java.net.URL;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import org.apache.druid.java.util.http.client.Request;
import org.jboss.netty.handler.codec.http.HttpMethod;

public class HttpRequestFactory {

    public static Request buildInternalRequest(final URL url, final byte[] body, final HttpServletRequest req) {
        Request request = new Request(HttpMethod.POST, url).setContent("application/json", body);

        Collections.list(req.getHeaderNames()).forEach(name -> {
            if (!request.getHeaders().containsKey(name)) {
                request.addHeaderValues(name, Collections.list(req.getHeaders(name)));
            }
        });

        return request;
    }
}

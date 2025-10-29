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
import org.apache.druid.java.util.http.client.Request;
import org.jboss.netty.handler.codec.http.HttpMethod;

public class HttpRequestFactory {

    public static Request buildInternalPost(URL url, byte[] body, Headers headers) {
        Request request = new Request(HttpMethod.POST, url).setContent("application/json", body);
        headers.applyTo(request);
        return request;
    }

    public static Request buildInternalGet(URL url, Headers headers) {
        Request request = new Request(HttpMethod.GET, url);
        headers.applyTo(request);
        return request;
    }

    public static Request buildInternalDelete(URL url, Headers headers) {
        Request request = new Request(HttpMethod.DELETE, url);
        headers.applyTo(request);
        return request;
    }
}

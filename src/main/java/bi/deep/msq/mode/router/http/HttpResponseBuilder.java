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

import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.druid.java.util.http.client.response.BytesFullResponseHolder;

public class HttpResponseBuilder {

    public static Response buildResult(final byte[] content) {
        return Response.ok(content).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    public static Response buildFailure(final String message, int statusCode) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(message)
                .type(MediaType.TEXT_PLAIN_TYPE)
                .status(statusCode)
                .build();
    }

    public static Response buildResponseFromFuture(final ListenableFuture<BytesFullResponseHolder> future)
            throws ExecutionException, InterruptedException {
        BytesFullResponseHolder holder = future.get();

        if (holder == null) {
            return Response.status(500).build();
        }

        Response.ResponseBuilder builder = Response.status(holder.getStatus().getCode());
        holder.getResponse().headers().forEach(h -> builder.header(h.getKey(), h.getValue()));

        if (ArrayUtils.isNotEmpty(holder.getContent())) {
            builder.entity(holder.getContent());
        }
        return builder.build();
    }
}

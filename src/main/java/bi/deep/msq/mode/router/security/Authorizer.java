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
package bi.deep.msq.mode.router.security;

import com.google.inject.Inject;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import org.apache.druid.server.security.Access;
import org.apache.druid.server.security.AuthorizationUtils;
import org.apache.druid.server.security.AuthorizerMapper;
import org.apache.druid.server.security.ForbiddenException;

public class Authorizer {
    private final AuthorizerMapper authorizerMapper;

    @Inject
    public Authorizer(AuthorizerMapper authorizerMapper) {
        this.authorizerMapper = authorizerMapper;
    }

    public void authorize(final HttpServletRequest req) {
        Access access = AuthorizationUtils.authorizeAllResourceActions(
                req, Collections.emptyList(), authorizerMapper); // switch to AuthorizationResult on druid upgrade

        if (!access.isAllowed()) {
            throw new ForbiddenException(access.toString());
        }
    }
}

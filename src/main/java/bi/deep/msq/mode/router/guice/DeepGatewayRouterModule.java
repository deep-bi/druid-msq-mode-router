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
package bi.deep.msq.mode.router.guice;

import bi.deep.msq.mode.router.resource.DeepGatewayRouterResource;
import bi.deep.msq.mode.router.security.Authorizer;
import com.fasterxml.jackson.databind.Module;
import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import com.google.inject.TypeLiteral;
import java.util.List;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.Jerseys;
import org.apache.druid.guice.JsonConfigProvider;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.ManageLifecycle;
import org.apache.druid.guice.RouterProcessingModule;
import org.apache.druid.guice.annotations.LoadScope;
import org.apache.druid.guice.http.HttpClientModule;
import org.apache.druid.initialization.DruidModule;
import org.apache.druid.server.router.AvaticaConnectionBalancer;
import org.apache.druid.server.router.QueryHostFinder;
import org.apache.druid.server.router.TieredBrokerConfig;
import org.apache.druid.server.router.TieredBrokerHostSelector;
import org.apache.druid.server.router.TieredBrokerSelectorStrategiesProvider;
import org.apache.druid.server.router.TieredBrokerSelectorStrategy;

@LoadScope(roles = NodeRole.ROUTER_JSON_NAME)
public class DeepGatewayRouterModule implements DruidModule {

    @Override
    public List<? extends Module> getJacksonModules() {
        // Register Jackson module for any classes we need to be able to use in JSON queries or ingestion specs.
        return ImmutableList.of();
    }

    @Override
    public void configure(Binder binder) {
        Jerseys.addResource(binder, DeepGatewayRouterResource.class);

        JsonConfigProvider.bind(binder, "druid.router.avatica.balancer", AvaticaConnectionBalancer.class);
        JsonConfigProvider.bind(binder, "druid.router", TieredBrokerConfig.class);

        binder.bind(Authorizer.class).in(LazySingleton.class);
        binder.bind(TieredBrokerHostSelector.class).in(ManageLifecycle.class);
        binder.bind(QueryHostFinder.class).in(LazySingleton.class);
        binder.bind(new TypeLiteral<List<TieredBrokerSelectorStrategy>>() {})
                .toProvider(TieredBrokerSelectorStrategiesProvider.class)
                .in(LazySingleton.class);
        binder.install(new RouterProcessingModule());
        binder.install(HttpClientModule.escalatedGlobal());
    }
}

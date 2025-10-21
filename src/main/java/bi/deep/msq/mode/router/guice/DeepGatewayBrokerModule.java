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

import bi.deep.msq.mode.router.config.DeepGatewayConfig;
import bi.deep.msq.mode.router.resource.DeepGatewayBrokerResource;
import bi.deep.msq.mode.router.security.Authorizer;
import com.fasterxml.jackson.databind.Module;
import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;
import java.util.List;
import org.apache.druid.client.BrokerSegmentWatcherConfig;
import org.apache.druid.client.InternalQueryConfig;
import org.apache.druid.client.selector.CustomTierSelectorStrategyConfig;
import org.apache.druid.client.selector.ServerSelectorStrategy;
import org.apache.druid.client.selector.TierSelectorStrategy;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.BrokerProcessingModule;
import org.apache.druid.guice.Jerseys;
import org.apache.druid.guice.JsonConfigProvider;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.annotations.EscalatedClient;
import org.apache.druid.guice.annotations.LoadScope;
import org.apache.druid.guice.http.HttpClientModule;
import org.apache.druid.initialization.DruidModule;
import org.apache.druid.query.RetryQueryRunnerConfig;

@LoadScope(roles = NodeRole.BROKER_JSON_NAME)
public class DeepGatewayBrokerModule implements DruidModule {

    private static final String NAMESPACE = "druid.deep.gateway";

    @Override
    public List<? extends Module> getJacksonModules() {
        // Register Jackson module for any classes we need to be able to use in JSON queries or ingestion specs.
        return ImmutableList.of();
    }

    @Override
    public void configure(Binder binder) {

        JsonConfigProvider.bind(binder, NAMESPACE, DeepGatewayConfig.class);

        binder.bind(Authorizer.class).in(LazySingleton.class);

        binder.bind(DeepGatewayBrokerModule.class).in(LazySingleton.class);
        Jerseys.addResource(binder, DeepGatewayBrokerResource.class);

        /* Druid requirements */

        // services/src/main/java/org/apache/druid/cli/CliBroker.java
        JsonConfigProvider.bind(binder, "druid.broker.select", TierSelectorStrategy.class);
        JsonConfigProvider.bind(binder, "druid.broker.select.tier.custom", CustomTierSelectorStrategyConfig.class);
        JsonConfigProvider.bind(binder, "druid.broker.balancer", ServerSelectorStrategy.class);
        JsonConfigProvider.bind(binder, "druid.broker.retryPolicy", RetryQueryRunnerConfig.class);
        JsonConfigProvider.bind(binder, "druid.broker.segment", BrokerSegmentWatcherConfig.class);
        JsonConfigProvider.bind(binder, "druid.broker.internal.query.config", InternalQueryConfig.class);

        binder.install(new BrokerProcessingModule());
        binder.install(new HttpClientModule("druid.broker.http", EscalatedClient.class, false));
    }
}

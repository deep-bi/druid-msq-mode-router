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
package bi.deep.msq.mode.router.helpers;

import com.google.common.base.Predicate;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import org.apache.druid.client.DruidServer;
import org.apache.druid.client.FilteredServerInventoryView;
import org.apache.druid.client.ServerInventoryView;
import org.apache.druid.client.ServerView;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.timeline.DataSegment;

public final class FakeInventoryView implements ServerInventoryView, FilteredServerInventoryView {

    private final ConcurrentMap<String, DruidServer> servers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<DataSegment>> loadedByServer = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ServerView.SegmentCallback> segmentCallbacks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SegmentCallbackRegistration> filteredSegmentCallbacks =
            new CopyOnWriteArrayList<>();
    private volatile boolean started;

    @Nullable
    @Override
    public DruidServer getInventoryValue(String key) {
        return servers.get(key);
    }

    @Override
    public Collection<DruidServer> getInventory() {
        return servers.values();
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public boolean isSegmentLoadedByServer(String serverKey, DataSegment segment) {
        Set<DataSegment> set = loadedByServer.get(serverKey);
        return set != null && set.contains(segment);
    }

    @Override
    public void registerSegmentCallback(
            Executor executor,
            ServerView.SegmentCallback segmentCallback,
            Predicate<Pair<DruidServerMetadata, DataSegment>> predicate) {
        filteredSegmentCallbacks.add(new SegmentCallbackRegistration(segmentCallback, predicate));
    }

    @Override
    public void registerServerRemovedCallback(Executor executor, ServerView.ServerRemovedCallback callback) {
        // No-op for simplicity
    }

    @Override
    public void registerSegmentCallback(Executor executor, SegmentCallback segmentCallback) {
        segmentCallbacks.add(segmentCallback);
    }

    public void addServer(DruidServer server) {
        servers.put(server.getName(), server);
        started = true;
    }

    public void segmentAdded(DruidServer server, DataSegment segment) {
        loadedByServer
                .computeIfAbsent(server.getName(), k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(segment);
        for (ServerView.SegmentCallback segmentCallback : segmentCallbacks) {
            segmentCallback.segmentAdded(server.getMetadata(), segment);
        }
        Pair<DruidServerMetadata, DataSegment> pair = Pair.of(server.getMetadata(), segment);
        for (SegmentCallbackRegistration reg : filteredSegmentCallbacks) {
            if (reg.filter.test(pair)) {
                reg.callback.segmentAdded(server.getMetadata(), segment);
            }
        }
    }

    public void signalInitialized() {
        for (ServerView.SegmentCallback segmentCallback : segmentCallbacks) {
            segmentCallback.segmentViewInitialized();
        }
        for (SegmentCallbackRegistration filteredSegmentCallback : filteredSegmentCallbacks) {
            filteredSegmentCallback.callback.segmentViewInitialized();
        }
    }
}

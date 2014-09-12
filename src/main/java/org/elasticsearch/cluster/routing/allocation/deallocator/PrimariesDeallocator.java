/*
   * Licensed to Elasticsearch under one or more contributor
   * license agreements. See the NOTICE file distributed with
   * this work for additional information regarding copyright
   * ownership. Elasticsearch licenses this file to you under
   * the Apache License, Version 2.0 (the "License"); you may
   * not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *    http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing,
   * software distributed under the License is distributed on an
   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
   * KIND, either express or implied.  See the License for the
   * specific language governing permissions and limitations
   * under the License.
   */

package org.elasticsearch.cluster.routing.allocation.deallocator;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.TransportClusterHealthAction;
import org.elasticsearch.action.admin.cluster.settings.TransportClusterUpdateSettingsAction;
import org.elasticsearch.action.admin.indices.settings.put.TransportUpdateSettingsAction;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This deallocator only deallocates primary shards that have no replica.
 * Other primary shards are not moved as their replicas can take over.
 *
 * Internally it excludes the local node for shard deallocation for every index with currently 0 replicas
 * that has shards on this node.
 * This will move the primary shards on this node to another but leaves everything else as is.
 */
public class PrimariesDeallocator extends AbstractDeallocator implements ClusterStateListener {

    private final TransportClusterHealthAction transportClusterHealthAction;
    private final Object localNodeFutureLock = new Object();
    private volatile SettableFuture<DeallocationResult> localNodeFuture;

    private final Object deallocatingIndicesLock = new Object();
    private volatile Map<String, Set<String>> deallocatingIndices;
    private final Set<String> newIndices = Sets.newConcurrentHashSet();

    private AtomicReference<String> allocationEnableSetting = new AtomicReference<>(EnableAllocationDecider.Allocation.ALL.name());


    @Inject
    public PrimariesDeallocator(ClusterService clusterService,
                                TransportClusterUpdateSettingsAction clusterUpdateSettingsAction,
                                TransportClusterHealthAction clusterHealthAction,
                                TransportUpdateSettingsAction indicesUpdateSettingsAction) {
        super(clusterService, indicesUpdateSettingsAction, clusterUpdateSettingsAction);
        this.deallocatingIndices = new ConcurrentHashMap<>();
        this.transportClusterHealthAction = clusterHealthAction;
        this.clusterService.add(this);
    }



    /**
     * return a set with all the indices that have zero replicas
     *
     * @param clusterMetaData the current clusterMetaData
     */
    private Set<String> zeroReplicaIndices(MetaData clusterMetaData) {
        final Set<String> zeroReplicaIndices = new HashSet<>();
        for (ObjectObjectCursor<String, IndexMetaData> entry : clusterMetaData.indices()) {
            if (entry.value.numberOfReplicas() == 0) {
                zeroReplicaIndices.add(entry.key);
            }
        }
        return zeroReplicaIndices;
    }

    /**
     * return a set with all the indices that have
     *
     *  * zero replicas
     *  * a shard (must be primary) on the local node
     *
     * @param clusterMetaData the current clusterMetaData
     */
    private Set<String> localZeroReplicaIndices(RoutingNode routingNode, MetaData clusterMetaData) {
        final Set<String> zeroReplicaIndices = new HashSet<>();
        for (ObjectObjectCursor<String, IndexMetaData> entry : clusterMetaData.indices()) {
            if (entry.value.numberOfReplicas() == 0) {
                if (!routingNode.shardsWithState(entry.key, ShardRoutingState.INITIALIZING, ShardRoutingState.STARTED, ShardRoutingState.RELOCATING).isEmpty()) {
                    zeroReplicaIndices.add(entry.key);
                }
            }
        }
        return zeroReplicaIndices;
    }

    private Set<String> localNewIndices(RoutingNode node, MetaData clusterMetaData) {
        final Set<String> newLocalIndices = new HashSet<>();
        synchronized (newIndices) {
            for (String index : newIndices) {
                IndexMetaData indexMetaData = clusterMetaData.index(index);
                if (indexMetaData != null && indexMetaData.numberOfReplicas() == 0) {
                    if (!node.shardsWithState(index, ShardRoutingState.INITIALIZING, ShardRoutingState.STARTED, ShardRoutingState.RELOCATING).isEmpty()) {
                        newLocalIndices.add(index);
                    }
                }
            }
        }
        return newLocalIndices;
    }

    @Override
    public ListenableFuture<DeallocationResult> deallocate() {
        if (isDeallocating()) {
            throw new IllegalStateException("node already waiting for primary only deallocation");
        }
        logger.info("[{}] starting primaries deallocation...", localNodeId());
        ClusterState state = clusterService.state();
        final RoutingNode node = state.routingNodes().node(localNodeId());
        if (node.size() == 0) {
            return Futures.immediateFuture(DeallocationResult.SUCCESS_NOTHING_HAPPENED);
        }
        MetaData clusterMetaData = state.metaData();
        if (localZeroReplicaIndices(node, clusterMetaData).isEmpty()) {
            // no zero replica primaries on node
            return Futures.immediateFuture(DeallocationResult.SUCCESS_NOTHING_HAPPENED);
        }

        final Set<String> zeroReplicaIndices = zeroReplicaIndices(clusterMetaData);
        
        // enable PRIMARIES allocation to make sure shards are moved, keep the old value
        allocationEnableSetting.set(
                clusterService.state().metaData().settings().get(
                        EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE,
                        EnableAllocationDecider.Allocation.ALL.name().toLowerCase(Locale.ENGLISH))); // global default
        setAllocationEnableSetting(EnableAllocationDecider.Allocation.PRIMARIES.name().toLowerCase(Locale.ENGLISH));

        synchronized (localNodeFutureLock) {
            localNodeFuture = SettableFuture.create();
            Futures.addCallback(localNodeFuture, new FutureCallback<DeallocationResult>() {
                @Override
                public void onSuccess(DeallocationResult result) {
                    resetAllocationEnableSetting();
                    newIndices.clear();
                }

                @Override
                public void onFailure(Throwable throwable) {
                    resetAllocationEnableSetting();
                    newIndices.clear();
                }
            });
        }
        excludeNodeFromIndices(zeroReplicaIndices, new ActionListener<UpdateSettingsResponse>() {
            @Override
            public void onResponse(UpdateSettingsResponse updateSettingsResponse) {
                logger.trace("successfully updated index settings");
                // do nothing
            }

            @Override
            public void onFailure(Throwable e) {
                logger.error("error updating index settings", e);
                cancelWithExceptionIfPresent(e);
            }
        });
        return localNodeFuture;
    }

    /**
     * configures the index so no shard will be allocated on the local node and existing
     * shards will be moved from it.
     * @param indices a set containing the indices which should be removed from the local node
     * @param listener an ActionListener that is called for every UpdateSettingsRequest
     */
    private void excludeNodeFromIndices(final Set<String> indices,
                                        ActionListener<UpdateSettingsResponse> listener) {
        UpdateSettingsRequest[] settingsRequests = new UpdateSettingsRequest[indices.size()];
        synchronized (deallocatingIndicesLock) {
            int i = 0;
            for (String index : indices) {
                Set<String> excludeNodes = deallocatingIndices.get(index);
                if (excludeNodes == null) {
                    excludeNodes = new HashSet<>();
                    deallocatingIndices.put(index, excludeNodes);
                }
                excludeNodes.add(localNodeId());
                settingsRequests[i++] = new UpdateSettingsRequest(
                        ImmutableSettings.builder()
                                .put(EXCLUDE_NODE_ID_FROM_INDEX, COMMA_JOINER.join(excludeNodes))
                                .build(),
                        index);
            }

        }
        if (settingsRequests.length > 0) {
            clusterChangeExecutor.enqueue(settingsRequests, updateSettingsAction, listener);
        }
    }

    private boolean cancelWithExceptionIfPresent(Throwable e) {
        boolean result = false;
        synchronized (localNodeFutureLock) {
            SettableFuture<DeallocationResult> future = localNodeFuture;
            if (future != null) {
                future.setException(e);
                localNodeFuture = null;
                result = true;
            }
        }
        return result;
    }

    private boolean cancelIfPresent() {
        boolean result = false;
        synchronized (localNodeFutureLock) {
            SettableFuture<DeallocationResult> future = localNodeFuture;
            if (future != null) {
                future.cancel(true);
                localNodeFuture = null;
                result = true;
            }
        }
        return result;
    }

    @Override
    public boolean cancel() {
        boolean cancelled = removeExclusion(localNodeId());
        cancelled |= cancelIfPresent();
        if (cancelled) {
            logger.info("[{}] primaries deallocation cancelled", localNodeId());
        } else {
            logger.debug("[{}] node not deallocating", localNodeId());
        }
        return cancelled;
    }

    private boolean removeExclusion(final String nodeId) {
        synchronized (deallocatingIndicesLock) {
            Set<String> changed = new HashSet<>();
            for (Map.Entry<String, Set<String>> entry : deallocatingIndices.entrySet()) {
                Set<String> excludeNodes = entry.getValue();
                if (excludeNodes.remove(nodeId)) {
                    changed.add(entry.getKey());
                }
                if (excludeNodes.isEmpty()) {
                    deallocatingIndices.remove(entry.getKey());
                }
            }
            if (!changed.isEmpty()) {
                UpdateSettingsRequest[] requests = new UpdateSettingsRequest[changed.size()];
                int i = 0;
                for (final String index : changed) {
                    Settings settings = ImmutableSettings.builder().put(EXCLUDE_NODE_ID_FROM_INDEX,
                            COMMA_JOINER.join(Objects.firstNonNull(deallocatingIndices.get(index), Collections.EMPTY_SET))).build();
                    requests[i++] = new UpdateSettingsRequest(settings, index);

                }
                clusterChangeExecutor.enqueue(requests,updateSettingsAction, new ActionListener<UpdateSettingsResponse>() {
                    @Override
                    public void onResponse(UpdateSettingsResponse updateSettingsResponse) {
                        logger.trace("[{}] excluded node {} from some index", localNodeId(), nodeId);
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        logger.error("[{}] error removing exclusion for node {}", e, localNodeId(), nodeId);
                    }
                });
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isDeallocating() {
        return localNodeFuture != null || localNodeIsExcluded();
    }

    /**
     * This deallocator can always deallocate
     */
    @Override
    public boolean canDeallocate() {
        return true;
    }

    /**
     * @return true if this node has no primary shards with 0 replicas
     * or no shards at all
     */
    @Override
    public boolean isNoOp() {
        ClusterState state = clusterService.state();
        RoutingNode node = state.routingNodes().node(localNodeId());
        return node.size() == 0 || localZeroReplicaIndices(node, state.metaData()).isEmpty();
    }

    private boolean localNodeIsExcluded() {
        synchronized (deallocatingIndicesLock) {
            for (Set<String> excludeNodes : deallocatingIndices.values()) {
                if (excludeNodes != null && excludeNodes.contains(localNodeId())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.metaDataChanged()) {
            synchronized (deallocatingIndicesLock) {
                // update deallocating nodes from new cluster state
                for (ObjectObjectCursor<String, IndexMetaData> entry : event.state().metaData().indices()) {
                    String excludeNodesSetting = entry.value.settings().get(EXCLUDE_NODE_ID_FROM_INDEX);
                    if (excludeNodesSetting != null) {
                        List<String> excludeNodes = COMMA_SPLITTER.splitToList(excludeNodesSetting);
                        if (!excludeNodes.isEmpty()) {
                            deallocatingIndices.put(entry.key, Sets.newHashSet(excludeNodes));
                        }
                    }
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("new deallocating indices: {}", COMMA_JOINER.withKeyValueSeparator(":").join(deallocatingIndices));
                }
            }
        }

        // exclusive master operation
        if (event.state().nodes().localNodeMaster()) {
            clusterChangedOnMaster(event);
        }

        if (localNodeFuture != null) { // not inside lock
            // add exclusion for all new indices, too
            List<String> createdIndices = event.indicesCreated();
            if (!createdIndices.isEmpty()) {
                newIndices.addAll(createdIndices);
                // wait for indices to become available first
                ClusterHealthRequest request = new ClusterHealthRequest(newIndices.toArray(new String[newIndices.size()]));
                request.waitForYellowStatus();
                request.timeout(new TimeValue(60 * 1000)); // wait 60 seconds max
                clusterChangeExecutor.enqueue(request, transportClusterHealthAction, new ActionListener<ClusterHealthResponse>() {
                    @Override
                    public void onResponse(ClusterHealthResponse clusterIndexHealths) {
                        if (clusterIndexHealths.isTimedOut()) {

                            // some of the new indices did not reach yellow state,
                            // we cannot fulfil the primaries min_availability, so give up
                            String errorMessages = Joiner.on('\n').join(clusterIndexHealths.getAllValidationFailures());
                            logger.trace("Some indices did not reach yellow state: {}", errorMessages);
                            cancelWithExceptionIfPresent(
                                    new DeallocationFailedException(
                                            String.format(Locale.ENGLISH,
                                                    "Some indices did not reach yellow state:\\n%s",
                                                    errorMessages
                                                    )
                                    )
                            );
                        }
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        logger.error("error waiting for yellow status on new indices", e);
                        cancelWithExceptionIfPresent(e);
                    }
                });
                // exclude localNode from new indices
                excludeNodeFromIndices(newIndices, new ActionListener<UpdateSettingsResponse>() {
                    @Override
                    public void onResponse(UpdateSettingsResponse updateSettingsResponse) {
                        logger.trace("successfully updated index settings for new index");
                        // do nothing
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        logger.error("error updating index settings for new index", e);
                        cancelWithExceptionIfPresent(e);
                    }
                });
            }
        }

        synchronized (localNodeFutureLock) {
            if (localNodeFuture != null) {

                RoutingNode node = event.state().routingNodes().node(localNodeId());
                MetaData clusterMetaData = event.state().metaData();
                Set<String> localZeroReplicaIndices = localZeroReplicaIndices(node, clusterMetaData);
                Set<String> localNewIndices = localNewIndices(node, clusterMetaData);

                if (localZeroReplicaIndices.isEmpty() && localNewIndices.isEmpty()) {
                    logger.info("[{}] primaries deallocation successful", localNodeId());
                    localNodeFuture.set(DeallocationResult.SUCCESS);
                    localNodeFuture = null;
                    newIndices.clear();
                } else {
                    logger.trace("[{}] zero replica primaries left for indices: {}", localNodeId(), COMMA_JOINER.join(localZeroReplicaIndices));
                }
            }
        }
    }

    /**
     * handle ClusterChangedEvent when local node is master
     */
    private void clusterChangedOnMaster(ClusterChangedEvent event) {
        // remove removed nodes from deallocatingNodes list if we are master
        if (event.nodesRemoved()) {
            for (DiscoveryNode node : event.nodesDelta().removedNodes()) {
                if (removeExclusion(node.id())) {
                    logger.trace("[{}] removed removed node {}", localNodeId(), node.id());
                }
            }
        }
    }
}

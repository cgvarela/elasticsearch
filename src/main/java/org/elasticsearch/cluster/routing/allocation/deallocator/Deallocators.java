/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package org.elasticsearch.cluster.routing.allocation.deallocator;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.inject.Inject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class Deallocators implements Deallocator {

    public static final String GRACEFUL_STOP_MIN_AVAILABILITY = "cluster.graceful_stop.min_availability";

    private final AllShardsDeallocator allShardsDeallocator;
    private final PrimariesDeallocator primariesDeallocator;
    private final ClusterService clusterService;

    private AtomicReference<Deallocator> pendingDeallocation = new AtomicReference<>();

    private Deallocator noOpDeallocator = new Deallocator() {
        @Override
        public ListenableFuture<DeallocationResult> deallocate() {
            return Futures.immediateFuture(DeallocationResult.SUCCESS_NOTHING_HAPPENED);
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean isDeallocating() {
            return false;
        }
    };

    @Inject
    public Deallocators(ClusterService clusterService, AllShardsDeallocator allShardsDeallocator, PrimariesDeallocator primariesDeallocator) {
        this.clusterService = clusterService;
        this.allShardsDeallocator = allShardsDeallocator;
        this.primariesDeallocator = primariesDeallocator;
    }

    @Override
    public ListenableFuture<Deallocator.DeallocationResult> deallocate() {
        final Deallocator deallocator = getDeallocator();
        if (!pendingDeallocation.compareAndSet(null, deallocator)) {
            throw new IllegalStateException("Node already deallocating");
        }
        ListenableFuture<DeallocationResult> future = deallocator.deallocate();
        Futures.addCallback(future, new FutureCallback<DeallocationResult>() {
            @Override
            public void onSuccess(DeallocationResult result) {
                pendingDeallocation.compareAndSet(deallocator, null);
            }

            @Override
            public void onFailure(Throwable throwable) {
                pendingDeallocation.compareAndSet(deallocator, null);
            }
        });
        return future;
    }

    @Override
    public boolean cancel() {
        Deallocator deallocator = pendingDeallocation.getAndSet(null);
        return deallocator != null && deallocator.cancel();
    }

    @Override
    public boolean isDeallocating() {
        Deallocator deallocator = pendingDeallocation.get();
        return deallocator != null && deallocator.isDeallocating();
    }

    private Deallocator getDeallocator() {
        Deallocator deallocator;
        // TODO: change default to primaries
        String minAvailability = clusterService.state().metaData().settings().get(GRACEFUL_STOP_MIN_AVAILABILITY, "full");
        switch (minAvailability) {
            case "primaries":
                deallocator = primariesDeallocator;
                break;
            case "full":
                deallocator = allShardsDeallocator;
                break;
            case "none":
                deallocator = noOpDeallocator;
                break;
            default:
                throw new IllegalArgumentException(String.format(Locale.ENGLISH, "invalid setting for '%s'", GRACEFUL_STOP_MIN_AVAILABILITY));
        }
        return deallocator;
    }
}

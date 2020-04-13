/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.deployment.services.provision;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.vmware.blockchain.deployment.services.futureutil.ReactiveStream;
import com.vmware.blockchain.deployment.services.orchestration.Orchestrator;
import com.vmware.blockchain.deployment.services.orchestration.OrchestratorData;

/**
 * Class implementation for deleting resources of a cluster.
 */
public class DeleteResource {

    /**
     * Delete Network Allocation.
     */
    public static CompletableFuture<List<OrchestratorData.NetworkAllocationEvent>> deleteNetworkAllocations(
        List<Map.Entry<Orchestrator, URI>> networkAllocList
    ) {
        List<CompletableFuture<OrchestratorData.NetworkAllocationEvent>> works = new ArrayList<>();
        networkAllocList.stream().forEach(entry -> {
            var orchestrator = entry.getKey();
            var resourceUri = entry.getValue();
            OrchestratorData.DeleteNetworkAllocationRequest networkDeallocReq =
                    new OrchestratorData.DeleteNetworkAllocationRequest(resourceUri);
            var deleteNetworkPublisher = orchestrator.deleteNetworkAllocation(networkDeallocReq);
            works.add(ReactiveStream.toFutureSingle(deleteNetworkPublisher));
        });

        return CompletableFuture.allOf(works.toArray(new CompletableFuture[works.size()]))
                .thenApply(res -> works.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /**
     * Delete Deployment.
     */
    public static CompletableFuture<List<OrchestratorData.ComputeResourceEvent>> deleteDeployments(
            List<Map.Entry<Orchestrator, URI>> computeList
    ) {
        List<CompletableFuture<OrchestratorData.ComputeResourceEvent>> works = new ArrayList<>();
        computeList.stream().forEach(entry -> {
            var orchestrator = entry.getKey();
            var resourceUri = entry.getValue();
            OrchestratorData.DeleteComputeResourceRequest delComputeResReq =
                    new OrchestratorData.DeleteComputeResourceRequest(resourceUri);
            var deleteDeploymentPublisher = orchestrator.deleteDeployment(delComputeResReq);
            works.add(CompletableFuture.supplyAsync(() -> deleteDeploymentPublisher));
        });

        return CompletableFuture.allOf(works.toArray(new CompletableFuture[works.size()]))
                .thenApply(res -> works.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /**
     * Delete Network Address.
     */
    public static CompletableFuture<List<OrchestratorData.NetworkResourceEvent>> deleteNetworkAddresses(
            List<Map.Entry<Orchestrator, URI>> networkAddrList
    ) {
        List<CompletableFuture<OrchestratorData.NetworkResourceEvent>> works = new ArrayList<>();
        networkAddrList.stream().forEach(entry -> {
            var orchestrator = entry.getKey();
            var resourceUri = entry.getValue();
            OrchestratorData.DeleteNetworkResourceRequest delNetworkResReq =
                    new OrchestratorData.DeleteNetworkResourceRequest(resourceUri);
            var deleteNetworkAddrPublisher = orchestrator.deleteNetworkAddress(delNetworkResReq);
            works.add(ReactiveStream.toFutureSingle(deleteNetworkAddrPublisher));
        });

        return CompletableFuture.allOf(works.toArray(new CompletableFuture[works.size()]))
                 .thenApply(res -> works.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }
}
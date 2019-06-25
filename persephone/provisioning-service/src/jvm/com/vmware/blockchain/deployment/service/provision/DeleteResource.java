/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.deployment.service.provision;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.blockchain.deployment.orchestration.Orchestrator;
import com.vmware.blockchain.deployment.reactive.ReactiveStream;

import kotlin.Pair;

class DeleteResource {
    private static Logger log = LoggerFactory.getLogger(DeleteResource.class);

    static List<Orchestrator.NetworkAllocationEvent> deleteNetworkAllocations(
        List<Pair<Orchestrator, URI>> networkAllocList
    ) {
        List<CompletableFuture<Orchestrator.NetworkAllocationEvent>> works = new ArrayList<>();
        networkAllocList.stream().forEach(entry -> {
            var orchestrator = entry.getFirst();
            var resourceUri = entry.getSecond();
            Orchestrator.DeleteNetworkAllocationRequest networkDeallocReq =
                    new Orchestrator.DeleteNetworkAllocationRequest(resourceUri);
            var deleteNetworkPublisher = orchestrator.deleteNetworkAllocation(networkDeallocReq);
            works.add(ReactiveStream.toFuture(deleteNetworkPublisher));
        });

        CompletableFuture<List<Orchestrator.NetworkAllocationEvent>> results =
                CompletableFuture.allOf(works.toArray(new CompletableFuture[works.size()]))
                .thenApply(res -> works.stream().map(CompletableFuture::join).collect(Collectors.toList()));
        return results.join();
    }

    static List<Orchestrator.ComputeResourceEvent> deleteDeployments(
            List<Pair<Orchestrator, URI>> computeList
    ) {
        List<CompletableFuture<Orchestrator.ComputeResourceEvent>> works = new ArrayList<>();
        computeList.stream().forEach(entry -> {
            var orchestrator = entry.getFirst();
            var resourceUri = entry.getSecond();
            Orchestrator.DeleteComputeResourceRequest delComputeResReq =
                    new Orchestrator.DeleteComputeResourceRequest(resourceUri);
            var deleteDeploymentPublisher = orchestrator.deleteDeployment(delComputeResReq);
            works.add(ReactiveStream.toFuture(deleteDeploymentPublisher));
        });

        CompletableFuture<List<Orchestrator.ComputeResourceEvent>> results =
                CompletableFuture.allOf(works.toArray(new CompletableFuture[works.size()]))
                        .thenApply(res -> works.stream().map(CompletableFuture::join).collect(Collectors.toList()));
        return results.join();
    }

    static List<Orchestrator.NetworkResourceEvent> deleteNetworkAddresses(
            List<Pair<Orchestrator, URI>> networkAddrList
    ) {
        List<CompletableFuture<Orchestrator.NetworkResourceEvent>> works = new ArrayList<>();
        networkAddrList.stream().forEach(entry -> {
            var orchestrator = entry.getFirst();
            var resourceUri = entry.getSecond();
            Orchestrator.DeleteNetworkResourceRequest delNetworkResReq =
                    new Orchestrator.DeleteNetworkResourceRequest(resourceUri);
            var deleteNetworkAddrPublisher = orchestrator.deleteNetworkAddress(delNetworkResReq);
            works.add(ReactiveStream.toFuture(deleteNetworkAddrPublisher));
        });

        CompletableFuture<List<Orchestrator.NetworkResourceEvent>> results =
                CompletableFuture.allOf(works.toArray(new CompletableFuture[works.size()]))
                        .thenApply(res -> works.stream().map(CompletableFuture::join).collect(Collectors.toList()));
        return results.join();
    }
}

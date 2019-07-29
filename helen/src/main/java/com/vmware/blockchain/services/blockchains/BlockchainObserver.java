/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.blockchains;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vmware.blockchain.auth.AuthHelper;
import com.vmware.blockchain.common.fleetmanagment.FleetUtils;
import com.vmware.blockchain.deployment.model.ConcordCluster;
import com.vmware.blockchain.deployment.model.ConcordNode;
import com.vmware.blockchain.deployment.model.DeploymentSession;
import com.vmware.blockchain.deployment.model.DeploymentSessionEvent;
import com.vmware.blockchain.operation.OperationContext;
import com.vmware.blockchain.services.blockchains.Blockchain.NodeEntry;
import com.vmware.blockchain.services.tasks.Task;
import com.vmware.blockchain.services.tasks.TaskService;

import io.grpc.stub.StreamObserver;

/**
 * Stream observer for creating a blockchain cluster.  Need to save the current Auth Context,
 * and update task and blockchain objects as they occur.
 */
public class BlockchainObserver implements StreamObserver<DeploymentSessionEvent> {
    private static final Logger logger = LogManager.getLogger(BlockchainObserver.class);

    private Authentication auth;
    private AuthHelper authHelper;
    private OperationContext operationContext;
    private BlockchainService blockchainService;
    private TaskService taskService;
    private UUID taskId;
    private UUID consortiumId;
    private final List<NodeEntry> nodeList = new ArrayList<>();
    private DeploymentSession.Status status = DeploymentSession.Status.UNKNOWN;
    private UUID clusterId;
    private String opId;

    /**
     * Create a new Blockchain Observer.  This handles the callbacks from the deployment
     * process.
     * @param blockchainService Blockchain service
     * @param taskService       Task service
     * @param taskId            The task ID we are reporting this on.
     * @param consortiumId      ID for the consortium creating this blockchain
     */
    public BlockchainObserver(
            AuthHelper authHelper,
            OperationContext operationContext,
            BlockchainService blockchainService,
            TaskService taskService,
            UUID taskId,
            UUID consortiumId) {
        this.authHelper = authHelper;
        this.operationContext = operationContext;
        this.blockchainService = blockchainService;
        this.taskService = taskService;
        this.taskId = taskId;
        this.consortiumId = consortiumId;
        auth = SecurityContextHolder.getContext().getAuthentication();
        opId = operationContext.getId();

    }

    @Override
    public void onNext(DeploymentSessionEvent value) {
        logger.info("On Next: {}", value.getType());
        // Set auth in this thread to whoever invoked the observer
        SecurityContextHolder.getContext().setAuthentication(auth);
        operationContext.setId(opId);
        try {
            switch (value.getType()) {
                case CLUSTER_DEPLOYED:
                    ConcordCluster cluster = value.getCluster();
                    // force the blockchain id to be the same as the cluster id
                    clusterId = FleetUtils.toUuid(cluster.getId());
                    logger.info("Blockchain ID: {}", clusterId);

                    cluster.getInfo().getMembers().stream()
                            .map(BlockchainObserver::toNodeEntry)
                            .peek(node -> logger.info("Node entry, id {}", node.getNodeId()))
                            .forEach(nodeList::add);
                    break;
                case COMPLETED:
                    status = value.getStatus();
                    logger.info("On Next(COMPLETED): status({})", status);
                    break;
                default:
                    break;
            }

            // Persist the current state of the task.
            final Task task = taskService.get(taskId);
            // Not clear if we need the merge here,
            taskService.merge(task, m -> {
                // if the latest entry is in completed, don't change anything
                if (m.getState() != Task.State.SUCCEEDED && m.getState() != Task.State.FAILED) {
                    // Otherwise, set the fields
                    m.setMessage(value.getType().name());
                }
            });
        } finally {
            SecurityContextHolder.getContext().setAuthentication(null);
            operationContext.removeId();
        }
    }

    @Override
    public void onError(Throwable t) {
        logger.info("On Error", t);
        // Set auth in this thread to whoever invoked the observer
        SecurityContextHolder.getContext().setAuthentication(auth);
        operationContext.setId(opId);
        final Task task = taskService.get(taskId);
        task.setState(Task.State.FAILED);
        task.setMessage(t.getMessage());
        taskService.merge(task, m -> {
            m.setState(task.getState());
            m.setMessage(task.getMessage());
        });
        SecurityContextHolder.getContext().setAuthentication(null);
        operationContext.removeId();
    }

    @Override
    public void onCompleted() {
        // Set auth in this thread to whoever invoked the observer
        SecurityContextHolder.getContext().setAuthentication(auth);
        operationContext.setId(opId);
        // Just log this.  Looking to see how often this happens.
        logger.info("Task {} completed, status {}", taskId, status);
        // We need to evict the auth token from the cache, since the available blockchains has just changed
        authHelper.evictToken();
        final Task task = taskService.get(taskId);
        task.setMessage("Operation finished");

        if (status == DeploymentSession.Status.SUCCESS) {
            // Create blockchain entity based on collected information.
            Blockchain blockchain = blockchainService.create(clusterId, consortiumId, nodeList);
            task.setResourceId(blockchain.getId());
            task.setResourceLink(String.format("/api/blockchains/%s", blockchain.getId()));
            task.setState(Task.State.SUCCEEDED);
        } else {
            task.setState(Task.State.FAILED);
        }

        // Persist the finality of the task, success or failure.
        taskService.merge(task, m -> {
            // if the latest entry is in completed, don't change anything
            if (m.getState() != Task.State.SUCCEEDED && m.getState() != Task.State.FAILED) {
                // Otherwise, set the fields
                m.setMessage(task.getMessage());
                m.setResourceId(task.getResourceId());
                m.setResourceLink(task.getResourceLink());
                m.setState(task.getState());
            }
        });
        //
        logger.info("Updated task {}", task);
        SecurityContextHolder.getContext().setAuthentication(null);
        operationContext.removeId();
    }

    /**
     * Convert a {@link ConcordNode} instance to a {@link NodeEntry} instance with applicable
     * property values transferred.
     *
     * @param node {@code ConcordNode} instance to convert from.
     *
     * @return     a new instance of {@code NodeEntry}.
     */
    private static NodeEntry toNodeEntry(ConcordNode node) {
        // Fetch the first IP address in the data payload, or return 0.
        var ip = toCanonicalIpAddress(node.getHostInfo().getIpv4AddressMap().keySet().stream()
                                              .findFirst().orElse(0));
        var endpoint = node.getHostInfo().getEndpoints().getOrDefault("ethereum-rpc", null);

        // For now, use the orchestration site ID as region name. Eventually there should be some
        // human-readable display name to go with the site ID.
        var site = node.getHostInfo().getSite();
        var region = FleetUtils.toUuid(site);

        return new NodeEntry(
                FleetUtils.toUuid(node.getId()),
                ip,
                endpoint.getUrl(),
                endpoint.getCertificate(),
                region
        );
    }

    /**
     * Simple conversion of a 4-byte value (expressed as an integer) to a canonical IPv4 address
     * format.
     *
     * @param value bytes to convert from.
     *
     * @return      canonical IP address format, as a {@link String} instance.
     */
    private static String toCanonicalIpAddress(int value) {
        var first = (value >>> 24);
        var second = (value & 0x00FF0000) >>> 16;
        var third = (value & 0x0000FF00) >>> 8;
        var fourth = (value & 0x000000FF);

        return String.format("%d.%d.%d.%d", first, second, third, fourth);
    }


}

/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.blockchains;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vmware.blockchain.common.ErrorCode;
import com.vmware.blockchain.common.ForbiddenException;
import com.vmware.blockchain.common.NotFoundException;
import com.vmware.blockchain.deployment.model.ConcordComponent;
import com.vmware.blockchain.deployment.model.ConcordModelSpecification;
import com.vmware.blockchain.deployment.model.CreateClusterRequest;
import com.vmware.blockchain.deployment.model.DeploymentSessionIdentifier;
import com.vmware.blockchain.deployment.model.DeploymentSpecification;
import com.vmware.blockchain.deployment.model.MessageHeader;
import com.vmware.blockchain.deployment.model.OrchestrationSiteIdentifier;
import com.vmware.blockchain.deployment.model.PlacementSpecification;
import com.vmware.blockchain.deployment.model.PlacementSpecification.Entry;
import com.vmware.blockchain.deployment.model.ProvisionServiceStub;
import com.vmware.blockchain.deployment.model.StreamClusterDeploymentSessionEventRequest;
import com.vmware.blockchain.deployment.model.ethereum.Genesis;
import com.vmware.blockchain.security.AuthHelper;
import com.vmware.blockchain.services.blockchains.Blockchain.NodeEntry;
import com.vmware.blockchain.services.profiles.Consortium;
import com.vmware.blockchain.services.profiles.ConsortiumService;
import com.vmware.blockchain.services.profiles.DefaultProfiles;
import com.vmware.blockchain.services.profiles.Roles;
import com.vmware.blockchain.services.tasks.Task;
import com.vmware.blockchain.services.tasks.Task.State;
import com.vmware.blockchain.services.tasks.TaskService;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Controller to create and list blockchains.
 */
@RestController
public class BlockchainController {
    private static final Logger logger = LogManager.getLogger(BlockchainController.class);

    /**
     * The type of sites we want in the deployment.
     */
    public enum DeploymentType {
        FIXED,
        UNSPECIFIED
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BlockchainPost {
        private UUID consortiumId;
        @JsonProperty("f_count")
        private int fCount;
        @JsonProperty("c_count")
        private int cCount;
        private DeploymentType deploymentType;
        private List<String> sites;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BlockchainPatch {
        private String ipList;
        private String rpcUrls;
        private String rpcCerts;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    static class BlockchainNodeEntry {
        private UUID nodeId;
        private String ip;
        private String url;
        private String cert;
        private String region;

        public BlockchainNodeEntry(NodeEntry n) {
            nodeId = n.getNodeId();
            ip = n.getIp();
            url = n.getUrl();
            cert = n.getCert();
            region = n.getRegion();
        }

    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    static class BlockchainGetResponse {
        private UUID id;
        private UUID consortiumId;
        private List<BlockchainNodeEntry> nodeList;

        public BlockchainGetResponse(Blockchain b) {
            this.id = b.getId();
            this.consortiumId = b.getConsortium();
            this.nodeList = b.getNodeList().stream().map(BlockchainNodeEntry::new).collect(Collectors.toList());
        }
    }


    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    static class BlockchainTaskResponse {
        private UUID taskId;
    }

    private BlockchainService manager;
    private ConsortiumService consortiumService;
    private AuthHelper authHelper;
    private DefaultProfiles defaultProfiles;
    private TaskService taskService;
    private ManagedChannel channel;

    /**
     * An observer to fake a blocked call.
     */
    private <T> StreamObserver<T> blockedResultObserver(CompletableFuture<T> result) {
        return new StreamObserver<>() {
            /** Holder of result value. */
            volatile T value;

            @Override
            public void onNext(T value) {
                this.value = value;
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onCompleted() {
                result.complete(value);
            }
        };
    }

    @Autowired
    public BlockchainController(BlockchainService manager,
            ConsortiumService consortiumService,
            AuthHelper authHelper,
            DefaultProfiles defaultProfiles,
            TaskService taskService,
            ManagedChannel channel) {
        this.manager = manager;
        this.consortiumService = consortiumService;
        this.authHelper = authHelper;
        this.defaultProfiles = defaultProfiles;
        this.taskService = taskService;
        this.channel = channel;
    }

    /**
     * Get the list of all blockchains.
     */
    @RequestMapping(path = "/api/blockchains", method = RequestMethod.GET)
    ResponseEntity<List<BlockchainGetResponse>> list() {
        List<Blockchain> chains = Collections.emptyList();
        // if we are operator, we can get all blockchains.
        if (authHelper.hasAnyAuthority(Roles.operatorRoles())) {
            chains = manager.list();
        } else {
            // Otherwise, we can only see our consortium.
            try {
                Consortium c = consortiumService.get(authHelper.getConsortiumId());
                chains = manager.listByConsortium(c);
            } catch (NotFoundException e) {
                // Just ignore
            }
        }
        List<BlockchainGetResponse> idList = chains.stream().map(BlockchainGetResponse::new)
                .collect(Collectors.toList());
        return new ResponseEntity<>(idList, HttpStatus.OK);
    }

    /**
     * Get the list of all blockchains.
     */
    @RequestMapping(path = "/api/blockchains/{id}", method = RequestMethod.GET)
    ResponseEntity<BlockchainGetResponse> get(@PathVariable UUID id) throws NotFoundException {
        if (!authHelper.hasAnyAuthority(Roles.operatorRoles()) && !authHelper.getPermittedChains().contains(id)) {
            throw new ForbiddenException(id + ErrorCode.UNALLOWED);
        }
        Blockchain b = manager.get(id);
        BlockchainGetResponse br = new BlockchainGetResponse(b);
        return new ResponseEntity<>(br, HttpStatus.OK);
    }

    /**
     * The actual call which will contact server and add the model request.
     */
    private DeploymentSessionIdentifier createFixedSizeCluster(ProvisionServiceStub client,
            int clusterSize) throws Exception {
        // Create a blocking stub with the channel
        List<Entry> list = new ArrayList<Entry>(clusterSize);
        for (int i = 0; i < clusterSize; i++) {
            list.add(
                    new Entry(PlacementSpecification.Type.UNSPECIFIED, new OrchestrationSiteIdentifier(1, 2)));
        }
        var placementSpec = new PlacementSpecification(list);
        var components = List.of(
                new ConcordComponent(ConcordComponent.Type.DOCKER_IMAGE, "vmwblockchain/concord-core:latest"),
                new ConcordComponent(ConcordComponent.Type.DOCKER_IMAGE, "vmwblockchain/ethrpc:latest"),
                new ConcordComponent(ConcordComponent.Type.DOCKER_IMAGE, "vmwblockchain/agent-testing:latest")
        );
        var genesis = new Genesis(
                new Genesis.Config(1, 0, 0, 0),
                "0x0000000000000000",
                "0x400",
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0xf4240",
                Map.of(
                        "262c0d7ab5ffd4ede2199f6ea793f819e1abb019", new Genesis.Wallet("12345"),
                        "5bb088f57365907b1840e45984cae028a82af934", new Genesis.Wallet("0xabcdef"),
                        "0000a12b3f3d6c9b0d3f126a83ec2dd3dad15f39", new Genesis.Wallet("0x7fffffffffffffff")
                )
        );
        ConcordModelSpecification spec = new ConcordModelSpecification(
                "20190401.1",
                "8abc7fda-9576-4b13-9beb-06f867cf2c7c",
                components
        );
        DeploymentSpecification deploySpec =
                new DeploymentSpecification(clusterSize, spec, placementSpec, genesis);
        var request = new CreateClusterRequest(new MessageHeader(), deploySpec);
        // Check that the API can be serviced normally after service initialization.
        var promise = new CompletableFuture<DeploymentSessionIdentifier>();
        client.createCluster(request, blockedResultObserver(promise));
        return promise.get();
    }

    /**
     * Create a new blockchain in the given consortium, with the specified nodes.
     * @throws Exception any exception
     */
    @RequestMapping(path = "/api/blockchains", method = RequestMethod.POST)
    public ResponseEntity<BlockchainTaskResponse> createBlockchain(@RequestBody BlockchainPost body) throws Exception {
        if (!authHelper.hasAnyAuthority(Roles.operatorRoles())) {
            throw new ForbiddenException(ErrorCode.UNALLOWED);
        }
        final ProvisionServiceStub client = new ProvisionServiceStub(channel, CallOptions.DEFAULT);
        // start the deployment
        int clusterSize = body.getFCount() * 3 + body.getCCount() * 2 + 1;
        logger.info("Creating new blockchain. Cluster size {}", clusterSize);
        DeploymentSessionIdentifier dsId = createFixedSizeCluster(client, clusterSize);
        logger.info("Deployment started, id {}", dsId);

        Task task = new Task();
        task.setState(Task.State.RUNNING);
        task = taskService.put(task);
        BlockchainObserver bo = new BlockchainObserver(authHelper, manager, taskService, task.getId());
        // Watch for the event stream
        StreamClusterDeploymentSessionEventRequest request =
                new StreamClusterDeploymentSessionEventRequest(new MessageHeader(), dsId);
        client.streamClusterDeploymentSessionEvents(request, bo);
        logger.info("Deployment scheduled");

        return new ResponseEntity<>(new BlockchainTaskResponse(task.getId()), HttpStatus.ACCEPTED);
    }

    /**
     * Update the given blockchain.
     */
    @RequestMapping(path = "/api/blockchains/{id}", method = RequestMethod.PATCH)
    public ResponseEntity<BlockchainTaskResponse> updateBlockchain(@PathVariable UUID id,
            @RequestBody BlockchainPatch body) throws NotFoundException {
        if (!authHelper.hasAnyAuthority(Roles.operatorRoles()) && !authHelper.getPermittedChains().contains(id)) {
            throw new ForbiddenException(ErrorCode.UNALLOWED);
        }


        // Temporary: create a completed task that points to the default bockchain
        Task task = new Task();
        task.setState(State.SUCCEEDED);
        task.setMessage("Default Blockchain");
        task.setResourceId(defaultProfiles.getBlockchain().getId());
        task.setResourceLink("/api/blockchains/".concat(defaultProfiles.getBlockchain().getId().toString()));
        task = taskService.put(task);

        return new ResponseEntity<>(new BlockchainTaskResponse(task.getId()), HttpStatus.ACCEPTED);
    }
}
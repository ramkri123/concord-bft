/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.deployment.service.configuration.server;

import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vmware.blockchain.deployment.model.ConfigurationComponent;
import com.vmware.blockchain.deployment.model.ConfigurationServiceImplBase;
import com.vmware.blockchain.deployment.model.ConfigurationServiceRequest;
import com.vmware.blockchain.deployment.model.ConfigurationServiceType;
import com.vmware.blockchain.deployment.model.ConfigurationSessionIdentifier;
import com.vmware.blockchain.deployment.model.Identity;
import com.vmware.blockchain.deployment.model.IdentityComponent;
import com.vmware.blockchain.deployment.model.IdentityFactors;
import com.vmware.blockchain.deployment.model.NodeConfigurationRequest;
import com.vmware.blockchain.deployment.model.NodeConfigurationResponse;
import com.vmware.blockchain.deployment.service.configuration.eccerts.ConcordEcCertificatesGenerator;
import com.vmware.blockchain.deployment.service.configuration.generateconfig.ConcordConfigUtil;

import io.grpc.stub.StreamObserver;

/**
 * Implementation of ConfigurationService server.
 */
public class ConfigurationService extends ConfigurationServiceImplBase {

    /**
     * Enumeration of possible service instance state.
     */
    private enum State {
        STOPPED,
        INITIALIZING,
        ACTIVE,
        STOPPING
    }

    /** Logger instance. */
    private static Logger log = LoggerFactory.getLogger(ConfigurationService.class);

    /** Atomic update for service instance state. */
    private static final AtomicReferenceFieldUpdater<ConfigurationService, State> STATE =
            AtomicReferenceFieldUpdater.newUpdater(ConfigurationService.class, State.class, "state");

    /** Executor to use for all async service operations. */
    private final ExecutorService executor;

    /** Service state. */
    private volatile State state = State.STOPPED;

    /** per session all node tls configuration. */
    private final Map<ConfigurationSessionIdentifier,
            Map<Integer, List<ConfigurationComponent>>> sessionConfig = new ConcurrentHashMap<>();

    /**
     * Constructor.
     */
    ConfigurationService(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Initialize the service instance asynchronously.
     *
     * @return
     *   {@link CompletableFuture} that completes when initialization is done.
     */
    CompletableFuture<Void> initialize() {
        if (STATE.compareAndSet(this, State.STOPPED, State.INITIALIZING)) {
            return CompletableFuture.runAsync(() -> {
                Security.addProvider(new BouncyCastleProvider());

                // Set instance to ACTIVE state.
                STATE.set(this, State.ACTIVE);

                log.info("ConfigurationService instance initialized");
            }, executor);
        } else {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ConfigurationService instance is not in stopped state")
            );
        }
    }

    /**
     * Shutdown the service instance asynchronously.
     *
     * @return
     *   {@link CompletableFuture} that completes when shutdown is done.
     */
    CompletableFuture<Void> shutdown() {
        if (STATE.compareAndSet(this, State.ACTIVE, State.STOPPING)
                || STATE.compareAndSet(this, State.INITIALIZING, State.STOPPING)) {
            return CompletableFuture.runAsync(() -> {
                log.info("ConfigurationService instance shutting down");
                Security.removeProvider("BC");

                // Set instance to STOPPED state.
                STATE.set(this, State.STOPPED);
            }, executor);
        } else {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ConfigurationService instance is not in active state")
            );
        }
    }

    @Override
    public void createConfiguration(ConfigurationServiceRequest message,
                                         StreamObserver<ConfigurationSessionIdentifier> observer) {

        var request = Objects.requireNonNull(message);
        var response = Objects.requireNonNull(observer);

        var sessionId = newSessionId();

        var certGen = new ConcordEcCertificatesGenerator();
        var identityFactor = certGen.getIdentityFactor();
        Map<Integer, List<ConfigurationComponent>> nodeComponent = new HashMap<>();

        // Generate TLS Configuration
        var configUtil = new ConcordConfigUtil();
        var tlsConfig = configUtil.generateConfigUtil(request.getHosts());

        List<Identity> tlsIdentityList = certGen.generateSelfSignedCertificates(configUtil.maxPrincipalId + 1,
                ConfigurationServiceType.DockerType.CONCORD_TLS);

        Map<Integer, List<IdentityComponent>> tlsNodeIdentities = buildTlsIdentity(tlsIdentityList,
                configUtil.nodePrincipal, configUtil.maxPrincipalId + 1);

        //Generate EthRPC Configuration
        List<Identity> ethrpcIdentityList = certGen.generateSelfSignedCertificates(request.getHosts().size(),
                ConfigurationServiceType.DockerType.ETHRPC);

        for (int node = 0; node < request.getHosts().size(); node++) {
            List<ConfigurationComponent> componentList = new ArrayList<>();

            // TLS list
            componentList.add(new ConfigurationComponent(
                    ConfigurationServiceType.DockerType.CONCORD_TLS,
                    configUtil.configPath, tlsConfig, new IdentityFactors()));

            tlsNodeIdentities.get(node).stream().forEach(entry -> {
                componentList.add(new ConfigurationComponent(
                        ConfigurationServiceType.DockerType.CONCORD_TLS,
                        entry.getUrl(),
                        entry.getBase64Value(),
                        identityFactor));
            });

            // ETHRPC list
            componentList.add(new ConfigurationComponent(
                    ConfigurationServiceType.DockerType.ETHRPC,
                    ethrpcIdentityList.get(node).getCertificate().getUrl(),
                    ethrpcIdentityList.get(node).getCertificate().getBase64Value(),
                    identityFactor
            ));

            componentList.add(new ConfigurationComponent(
                    ConfigurationServiceType.DockerType.ETHRPC,
                    ethrpcIdentityList.get(node).getKey().getUrl(),
                    ethrpcIdentityList.get(node).getKey().getBase64Value(),
                    identityFactor
            ));

            // put per node configs
            nodeComponent.putIfAbsent(node, componentList);

        }

        var persist = sessionConfig.putIfAbsent(sessionId, nodeComponent);

        if (persist == null && persist == null) {
            response.onNext(sessionId);
            response.onCompleted();
        } else {
            response.onError(new IllegalStateException("Could not persist configuration results"));
        }
    }

    @Override
    public void getNodeConfiguration(NodeConfigurationRequest nodeRequest,
                                     StreamObserver<NodeConfigurationResponse> observer) {

        var request = Objects.requireNonNull(nodeRequest);
        var response = Objects.requireNonNull(observer);

        var components = sessionConfig.get(nodeRequest.getIdentifier());

        var nodeComponents = components.get(request.getNode());

        if (nodeComponents.size() != 0) {
            response.onNext(new NodeConfigurationResponse(nodeComponents));
            response.onCompleted();
        } else {
            response.onError(new IllegalStateException("Could not retrieve configuration results"));
        }
    }

    /**
     * Generate a new {@link ConfigurationSessionIdentifier}.
     *
     * @return
     *   a new {@link ConfigurationSessionIdentifier} instance.
     */
    private static ConfigurationSessionIdentifier newSessionId() {
        return new ConfigurationSessionIdentifier(new Random().nextLong()); //use secure random
    }

    /**
     * Get the identity component list for all nodes.
     *
     * @param identities : identity list for all identities
     * @param principals : the principal map by configUtil
     * @param  numCerts : number of total certificate/keys
     * @return : map of node vs identity component list
     */
    private Map<Integer, List<IdentityComponent>>  buildTlsIdentity(
            List<Identity> identities,
            Map<Integer, List<Integer>> principals,
            int numCerts) {

        Map<Integer, List<IdentityComponent>> result = new HashMap<>();
        for (int node : principals.keySet()) {
            List<IdentityComponent> nodeIdentities = new ArrayList<>();

            List<Integer> notPrincipal = IntStream.range(0, numCerts)
                    .boxed().collect(Collectors.toList());
            notPrincipal.removeAll(principals.get(node));

            List<Identity> serverList = new ArrayList<>(identities.subList(0, identities.size() / 2));
            List<Identity> clientList = new ArrayList<>(identities.subList(identities.size() / 2, identities.size()));

            notPrincipal.stream().forEach(entry -> {
                nodeIdentities.add(serverList.get(entry).getCertificate());
                nodeIdentities.add(clientList.get(entry).getCertificate());
            });

            // add self keys
            nodeIdentities.add(serverList.get(node).getKey());
            nodeIdentities.add(clientList.get(node).getKey());

            principals.get(node).stream().forEach(entry -> {
                nodeIdentities.add(serverList.get(entry).getCertificate());
                nodeIdentities.add(serverList.get(entry).getKey());
                nodeIdentities.add(clientList.get(entry).getCertificate());
                nodeIdentities.add(clientList.get(entry).getKey());
            });
            result.putIfAbsent(node, nodeIdentities);
        }
        return result;
    }
}
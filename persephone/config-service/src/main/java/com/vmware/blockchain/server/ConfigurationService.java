/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.server;

import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.validation.constraints.NotNull;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.vmware.blockchain.configuration.eccerts.ConcordEcCertificatesGenerator;
import com.vmware.blockchain.configuration.generateconfig.ConcordConfigUtil;
import com.vmware.blockchain.configuration.generateconfig.DamlIndexDbUtil;
import com.vmware.blockchain.configuration.generateconfig.DamlLedgerApiUtil;
import com.vmware.blockchain.configuration.generateconfig.GenesisUtil;
import com.vmware.blockchain.configuration.generateconfig.LoggingUtil;
import com.vmware.blockchain.configuration.generateconfig.TelegrafConfigUtil;

import com.vmware.blockchain.deployment.v1.ConcordComponent.ServiceType;
import com.vmware.blockchain.deployment.v1.ConfigurationComponent;
import com.vmware.blockchain.deployment.v1.ConfigurationServiceGrpc.ConfigurationServiceImplBase;
import com.vmware.blockchain.deployment.v1.ConfigurationServiceRequest;
import com.vmware.blockchain.deployment.v1.ConfigurationSessionIdentifier;
import com.vmware.blockchain.deployment.v1.DeleteConfigurationRequest;
import com.vmware.blockchain.deployment.v1.DeleteConfigurationResponse;
import com.vmware.blockchain.deployment.v1.Identity;
import com.vmware.blockchain.deployment.v1.IdentityComponent;
import com.vmware.blockchain.deployment.v1.IdentityFactors;
import com.vmware.blockchain.deployment.v1.NodeConfigurationRequest;
import com.vmware.blockchain.deployment.v1.NodeConfigurationResponse;
import com.vmware.blockchain.ethereum.type.Genesis;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of ConfigurationService server.
 */
@GRpcService
@Slf4j
public class ConfigurationService extends ConfigurationServiceImplBase {

    /** Concord config Template path. **/
    // FIXME: Rename to concordConfigPath
    private String configPath;

    /** Telegraf config template path. **/
    private String telegrafConfigPath;

    /** Metrics config template path. **/
    private String metricsConfigPath;


    /** Executor to use for all async service operations. */
    private final ExecutorService executor;

    /** per session all node tls configuration. */
    private final Map<ConfigurationSessionIdentifier,
            Map<Integer, List<ConfigurationComponent>>> sessionConfig = new ConcurrentHashMap<>();

    /**
     * Constructor.
     */
    @Autowired
    ConfigurationService(ExecutorService executor,
                         @Value("${config.template.path:ConcordConfigTemplate.yaml}")
                                 String configTemplatePath,
                         @Value("${config.template.path:TelegrafConfigTemplate.config}")
                                 String telegrafConfigTemplatePath,
                         @Value("${config.template.path:MetricsConfig.yaml}")
                                 String metricsConfigPath)  {
        this.configPath = configTemplatePath;
        this.telegrafConfigPath = telegrafConfigTemplatePath;
        this.metricsConfigPath = metricsConfigPath;
        this.executor = executor;
        initialize();
    }

    /**
     * Initialize the service instance asynchronously.
     *
     * @return
     *   {@link CompletableFuture} that completes when initialization is done.
     */
    CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            Security.addProvider(new BouncyCastleProvider());
            log.info("ConfigurationService instance initialized");
        }, executor);
    }

    @Override
    public void createConfiguration(@NotNull ConfigurationServiceRequest request,
                                    @NotNull StreamObserver<ConfigurationSessionIdentifier> observer) {

        var sessionId = newSessionId();
        List<ConfigurationComponent> staticComponentList = new ArrayList<>();

        log.info(request.toString());
        // Static settings for each service Type.
        for (ServiceType serviceType : request.getServicesList()) {

            switch (serviceType) {
                case DAML_LEDGER_API:
                    DamlLedgerApiUtil ledgerApiUtil = new DamlLedgerApiUtil(
                            request.getProperties().getValues().get(DamlLedgerApiUtil.REPLICAS_KEY));
                    staticComponentList.add(ConfigurationComponent.newBuilder()
                                                    .setType(serviceType)
                                            .setComponentUrl(DamlLedgerApiUtil.envVarPath)
                                            .setComponent(ledgerApiUtil.generateConfig())
                                            .setIdentityFactors(IdentityFactors.newBuilder().build())
                                            .build());
                    break;
                case DAML_INDEX_DB:
                    DamlIndexDbUtil damlIndexDbUtil = new DamlIndexDbUtil();
                    staticComponentList.add(ConfigurationComponent.newBuilder()
                                                    .setType(serviceType)
                                                    .setComponentUrl(DamlIndexDbUtil.envVarPath)
                                                    .setComponent(damlIndexDbUtil.generateConfig())
                                                    .setIdentityFactors(IdentityFactors.newBuilder().build())
                                                    .build());
                    break;
                case LOGGING:
                    LoggingUtil loggingUtil =
                            new LoggingUtil(request.getProperties().getValues().get(LoggingUtil.LOGGING_CONFIG));
                    staticComponentList.add(ConfigurationComponent.newBuilder()
                                                    .setType(ServiceType.LOGGING)
                                                    .setComponentUrl(LoggingUtil.envVarPath)
                                                    .setComponent(loggingUtil.generateConfig())
                                                    .setIdentityFactors(IdentityFactors.newBuilder().build())
                                                    .build());
                    break;
                case CONCORD:
                    log.info("Generated new session id {}", sessionId);
                    ConfigurationComponent genesisJson = createGenesisComponent(request.getGenesis(), sessionId);
                    staticComponentList.add(genesisJson);
                    break;
                case ETHEREUM_API:
                    staticComponentList.addAll(getEthereumComponent());
                    break;
                default:
                    log.info("No config required for serviceType {}", serviceType);
            }
        }

        Map<Integer, List<ConfigurationComponent>> nodeComponent = new HashMap<>();

        if (request.getServicesList().contains(ServiceType.CONCORD)
            || request.getServicesList().contains(ServiceType.DAML_CONCORD)
            || request.getServicesList().contains(ServiceType.HLF_CONCORD)
            || request.getServicesList().contains(ServiceType.TELEGRAF)) {

            var certGen = new ConcordEcCertificatesGenerator();

            // Generate Configuration
            var configUtil = new ConcordConfigUtil(configPath);
            var tlsConfig = configUtil.getConcordConfig(request.getHostsList(), request.getBlockchainType());

            // Generate telegraf config
            var telegrafConfigUtil = new TelegrafConfigUtil(telegrafConfigPath, metricsConfigPath);
            var metricsConfigYaml = telegrafConfigUtil.getMetricsConfigYaml();
            var telegrafConfig = telegrafConfigUtil.getTelegrafConfig(request.getHostsList());

            List<Identity> tlsIdentityList =
                    generateEtheriumConfig(certGen, configUtil.maxPrincipalId + 1, ServiceType.CONCORD);
            log.info("Generated tls identity elements for session id: {}", sessionId);

            Map<Integer, List<IdentityComponent>> tlsNodeIdentities = buildTlsIdentity(tlsIdentityList,
                                                                                       configUtil.nodePrincipal,
                                                                                       configUtil.maxPrincipalId + 1,
                                                                                       request.getHostsList().size());

            for (int node = 0; node < request.getHostsList().size(); node++) {
                List<ConfigurationComponent> componentList = new ArrayList<>();
                componentList.addAll(staticComponentList);

                // TLS list
                componentList.add(ConfigurationComponent.newBuilder()
                        .setType(ServiceType.CONCORD)
                        .setComponentUrl(configUtil.configPath)
                        .setComponent(tlsConfig.get(node))
                        .setIdentityFactors(IdentityFactors.newBuilder().build())
                        .build());

                tlsNodeIdentities.get(node)
                        .forEach(entry -> componentList.add(
                                ConfigurationComponent.newBuilder()
                                        .setType(ServiceType.CONCORD)
                                        .setComponentUrl(entry.getUrl())
                                        .setComponent(entry.getBase64Value())
                                        .setIdentityFactors(certGen.getIdentityFactor())
                                        .build()
                        ));

                // telegraf configs
                componentList.add(ConfigurationComponent.newBuilder()
                        .setType(ServiceType.TELEGRAF)
                        .setComponentUrl(telegrafConfigUtil.configPath)
                        .setComponent(telegrafConfig.get(node))
                        .setIdentityFactors(IdentityFactors.newBuilder().build())
                        .build());

                componentList.add(ConfigurationComponent.newBuilder()
                        .setType(ServiceType.TELEGRAF)
                        .setComponentUrl(TelegrafConfigUtil.metricsConfigPath)
                        .setComponent(metricsConfigYaml)
                        .setIdentityFactors(IdentityFactors.newBuilder().build())
                        .build());

                // put per node configs
                nodeComponent.putIfAbsent(node, componentList);
                log.info("Created configurations for session: {}", sessionId);

            }

        } else {
            for (int node = 0; node < request.getHostsList().size(); node++) {
                List<ConfigurationComponent> componentList = new ArrayList<>();
                componentList.addAll(staticComponentList);
                nodeComponent.putIfAbsent(node, componentList);
                log.info("Created configurations for session: {}", sessionId);
            }
        }

        // Error out if no configurations are generated.
        if (staticComponentList.isEmpty()) {
            String msg = "No configurations were generated for servive type(s)" + request.getServicesList();
            log.error(msg);
            observer.onError(new StatusException(Status.INVALID_ARGUMENT.withDescription(msg)));
        } else {
            log.info("Persisting configurations for session: {} in memory...", sessionId);
            var persist = sessionConfig.putIfAbsent(sessionId, nodeComponent);

            if (persist == null) {
                log.info("Persisted configurations for session: {} in memory.", sessionId);
                observer.onNext(sessionId);
                observer.onCompleted();
            } else {
                observer.onError(new StatusException(
                        Status.INTERNAL.withDescription("Could not persist configuration results")));
            }
        }
    }

    private List<ConfigurationComponent> getEthereumComponent() {
        List<ConfigurationComponent> output = new ArrayList<>();

        var certGen = new ConcordEcCertificatesGenerator();
        List<Identity> ethrpcIdentityList =
                generateEtheriumConfig(certGen, 1, ServiceType.ETHEREUM_API);

        output.add(ConfigurationComponent.newBuilder()
                        .setType(ServiceType.ETHEREUM_API)
                        .setComponentUrl(ethrpcIdentityList.get(0).getCertificate().getUrl())
                        .setComponent(ethrpcIdentityList.get(0).getCertificate().getBase64Value())
                        .setIdentityFactors(certGen.getIdentityFactor())
                        .build());

        output.add(ConfigurationComponent.newBuilder()
                           .setType(ServiceType.ETHEREUM_API)
                           .setComponentUrl(ethrpcIdentityList.get(0).getKey().getUrl())
                           .setComponent(ethrpcIdentityList.get(0).getKey().getBase64Value())
                           .setIdentityFactors(certGen.getIdentityFactor())
                           .build());

        return output;
    }

    private List<Identity> generateEtheriumConfig(ConcordEcCertificatesGenerator certGen, int size,
                                                  ServiceType ethereumApi) {
        //Generate EthRPC Configuration
        return certGen.generateSelfSignedCertificates(size,
                                                      ethereumApi);
    }

    private ConfigurationComponent createGenesisComponent(@NotNull Genesis genesis,
                                          ConfigurationSessionIdentifier sessionId) {
        var genesisUtil = new GenesisUtil();
        log.info("Generated genesis for session id: {}", sessionId);

        return ConfigurationComponent.newBuilder()
                .setType(ServiceType.CONCORD)
                .setComponentUrl(GenesisUtil.genesisPath)
                .setComponent(genesisUtil.getGenesis(genesis))
                .setIdentityFactors(IdentityFactors.newBuilder().build())
                .build();
    }

    @Override
    public void getNodeConfiguration(@NotNull NodeConfigurationRequest request,
                                     @NotNull StreamObserver<NodeConfigurationResponse> observer) {

        try {
            var components = sessionConfig.get(request.getIdentifier());
            log.info("Configurations found for session id {}", request.getIdentifier());

            var nodeComponents = components.get(request.getNode());

            if (nodeComponents.size() != 0) {
                NodeConfigurationResponse.Builder builder = NodeConfigurationResponse.newBuilder();
                builder.addAllConfigurationComponent(nodeComponents);

                observer.onNext(builder.build());
                observer.onCompleted();
            } else {
                observer.onError(new StatusException(Status.NOT_FOUND.withDescription(
                        "Node configuration is empty for node: " + request.getNode())));
            }
        } catch (Exception e) {
            var errorMsg = String.format("Retrieving configuration results failed for id: %s with error: %s",
                    request.getIdentifier(), e.getLocalizedMessage());
            observer.onError(new StatusException(Status.INVALID_ARGUMENT.withDescription(errorMsg)));
        }

    }

    @Override
    public void deleteConfiguration(
            @NotNull DeleteConfigurationRequest request,
            @NotNull StreamObserver<DeleteConfigurationResponse> observer) {

        try {
            sessionConfig.remove(request.getId());
            log.info("Deleted configurations for session id: {}", request.getId());
            observer.onNext(DeleteConfigurationResponse.newBuilder().build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(new StatusException(
                    Status.NOT_FOUND.withDescription("No configuration available for session id: " + request.getId())));
        }
    }

    /**
    * Generate a new {@link ConfigurationSessionIdentifier}.
    *
    * @return
    *   a new {@link ConfigurationSessionIdentifier} instance.
    */
    private static ConfigurationSessionIdentifier newSessionId() {
        return ConfigurationSessionIdentifier.newBuilder().setIdentifier(new SecureRandom().nextLong()).build();
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
            int numCerts, int numHosts) {

        Map<Integer, List<IdentityComponent>> result = new HashMap<>();

        // TODO: May remove logic once principals are available
        if (principals.size() == 0) {
            IntStream.range(0, numHosts).forEach(node -> {
                List<IdentityComponent> identityComponents = new ArrayList<>();
                identities.forEach(identity -> {
                    identityComponents.add(identity.getCertificate());
                    identityComponents.add(identity.getKey());
                });
                result.put(node, identityComponents);
            });
            return result;
        }

        for (int node : principals.keySet()) {
            List<IdentityComponent> nodeIdentities = new ArrayList<>();

            List<Integer> notPrincipal = IntStream.range(0, numCerts)
                    .boxed().collect(Collectors.toList());
            notPrincipal.removeAll(principals.get(node));

            List<Identity> serverList = new ArrayList<>(identities.subList(0, identities.size() / 2));
            List<Identity> clientList = new ArrayList<>(identities.subList(identities.size() / 2, identities.size()));

            notPrincipal.forEach(entry -> {
                nodeIdentities.add(serverList.get(entry).getCertificate());
                nodeIdentities.add(clientList.get(entry).getCertificate());
            });

            // add self keys
            nodeIdentities.add(serverList.get(node).getKey());
            nodeIdentities.add(clientList.get(node).getKey());

            principals.get(node).forEach(entry -> {
                nodeIdentities.add(serverList.get(entry).getCertificate());
                nodeIdentities.add(serverList.get(entry).getKey());
                nodeIdentities.add(clientList.get(entry).getCertificate());
                nodeIdentities.add(clientList.get(entry).getKey());
            });
            result.putIfAbsent(node, nodeIdentities);
        }

        log.info("Filtered tls identities based on nodes and principal ids.");
        return result;
    }
}
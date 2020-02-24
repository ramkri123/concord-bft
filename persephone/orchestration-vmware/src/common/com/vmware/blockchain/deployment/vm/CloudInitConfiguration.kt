/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.vm

import com.vmware.blockchain.deployment.model.core.URI
import com.vmware.blockchain.deployment.orchestration.toIPv4Address
import com.vmware.blockchain.deployment.orchestration.toIPv4SubnetMask
import com.vmware.blockchain.deployment.v1.ConcordAgentConfiguration
import com.vmware.blockchain.deployment.v1.ConcordClusterIdentifier
import com.vmware.blockchain.deployment.v1.ConcordComponent
import com.vmware.blockchain.deployment.v1.ConcordModelSpecification
import com.vmware.blockchain.deployment.v1.ConcordNodeIdentifier
import com.vmware.blockchain.deployment.v1.ConfigurationSessionIdentifier
import com.vmware.blockchain.deployment.v1.Credential
import com.vmware.blockchain.deployment.v1.Endpoint
import com.vmware.blockchain.deployment.v1.LogManagement
import com.vmware.blockchain.deployment.v1.OutboundProxyInfo
import com.vmware.blockchain.deployment.v1.Wavefront
import java.util.*

/**
 * Initialization script run either on first-boot of a deployed virtual machine.
 */
class CloudInitConfiguration(
    containerRegistry: Endpoint,
    model: ConcordModelSpecification,
    ipAddress: String,
    gateway: String,
    nameServers: List<String>,
    subnet: Int,
    clusterId: ConcordClusterIdentifier,
    private val replicaId: ConcordNodeIdentifier,
    nodeId: Int,
    configGenId: ConfigurationSessionIdentifier,
    configServiceEndpoint: Endpoint,
    configServiceRestEndpoint: Endpoint,
    outboundProxy: OutboundProxyInfo,
    logManagements : List<LogManagement>,
    wavefront: Wavefront,
    private val consortium: String
) {

    private val agentImageName: String = model.componentsList
            .filter { it.type == ConcordComponent.Type.CONTAINER_IMAGE }
            .filter { it.serviceType == ConcordComponent.ServiceType.GENERIC }.first().name

    private val networkSetupCommand: String by lazy {
        var dnsEntry = ""
        nameServers.takeIf { !it.isNullOrEmpty() }
                ?.let {
                    val dnsString = nameServers.joinToString(separator = " ")
                    dnsEntry = "\\nDNS=$dnsString"
                }
        "echo -e \"[Match]\\nName=eth0\\n\\n[Network]\\nAddress=$ipAddress/$subnet" +
                "\\nGateway=$gateway $dnsEntry\" > /etc/systemd/network/10-eth0-static.network; " +
                "chmod 644 /etc/systemd/network/10-eth0-static.network; systemctl restart systemd-networkd;"
    }

    private val dockerDnsSetupCommand: String by lazy {
        nameServers.takeIf { !it.isNullOrEmpty() }
                ?.let {
                    nameServers.joinToString(separator = " --dns ",
                            prefix = "--dns ")
                }
                ?: ""
    }

    private val setupOutboundProxy: String by lazy {
        outboundProxy.takeIf { !outboundProxy.httpsHost.isNullOrBlank() }
                ?.let {
                    "export http_proxy=${outboundProxy.httpHost}:${outboundProxy.httpPort}" +
                            ";export https_proxy=${outboundProxy.httpsHost}:${outboundProxy.httpsPort}" +
                            ";mkdir /etc/systemd/system/docker.service.d" +
                            ";echo -e '[Service]\\nEnvironment=\"HTTP_PROXY=http://${outboundProxy.httpHost}:${outboundProxy.httpPort}\"\\nEnvironment=\"HTTPS_PROXY=http://${outboundProxy.httpsHost}:${outboundProxy.httpsPort}\"\\nEnvironment=\"NO_PROXY=localhost,127.0.0.1\"' > /etc/systemd/system/docker.service.d/http-proxy.conf"
                }
                ?: ""
    }

    /** Concord agent startup configuration parameters. */
    private val configuration: ConcordAgentConfiguration = ConcordAgentConfiguration.newBuilder()
            .setModel(model)
            .setContainerRegistry(containerRegistry)
            .setCluster(clusterId)
            .setNode(nodeId)
            .setConfigService(outboundProxy.takeIf { outboundProxy.httpsHost.isNullOrBlank() }
                    ?.let {
                        configServiceEndpoint
                    }
                    ?: configServiceRestEndpoint)
            .setConfigurationSession(configGenId)
            .setOutboundProxyInfo(outboundProxy)
            .addAllLoggingEnvVariables(logManagements.loggingEnvVariablesSetup())
            .setWavefront(Wavefront.newBuilder().setUrl(wavefront.url).setToken(wavefront.token).build()).build()

    private val script =
            """
            #!/bin/sh
            echo -e "c0nc0rd\nc0nc0rd" | /bin/passwd

            {{networkSetupCommand}}

            sed -i 's_/usr/bin/dockerd.*_/usr/bin/dockerd {{dockerDns}} -H tcp://127.0.0.1:2375 -H unix:///var/run/docker.sock {{registrySecuritySetting}}_g' /lib/systemd/system/docker.service

            {{setupOutboundProxy}}
            systemctl daemon-reload

            mkdir -p /etc/docker
            echo '{"log-driver": "json-file", "log-opts": { "max-size": "100m", "max-file": "5"}}' > /etc/docker/daemon.json
            systemctl restart docker
            
            # To enable docker on boot.
            systemctl enable docker

            {{dockerLoginCommand}}

            # Output the node's model specification.
            mkdir -p /config/agent
            echo '{{agentConfig}}' > /config/agent/config.json

            # Update guest-info's network information in vSphere.
            touch /etc/vmware-tools/tools.conf
            printf '[guestinfo]\nprimary-nics=eth*\nexclude-nics=docker*,veth*' > /etc/vmware-tools/tools.conf
            /usr/bin/vmware-toolbox-cmd info update network

            docker run -d --name=agent --restart=always -v /config:/config -v /var/run/docker.sock:/var/run/docker.sock -p 8546:8546 {{agentImage}}
            echo 'done'
            """.trimIndent()
                    .replace("{{dockerLoginCommand}}", containerRegistry.toRegistryLoginCommand())
                    .replace("{{registrySecuritySetting}}", containerRegistry.toRegistrySecuritySetting())
                    .replace("{{agentImage}}", URI.create(containerRegistry.address).authority + "/" + agentImageName)
                    .replace("{{agentConfig}}", com.google.protobuf.util.JsonFormat.printer().print(configuration))
                    .replace("{{networkSetupCommand}}", networkSetupCommand)
                    .replace("{{dockerDns}}", dockerDnsSetupCommand)
                    .replace("{{setupOutboundProxy}}", setupOutboundProxy)

    /**
     * Convert an endpoint to a Docker Registry login command.
     *
     * @return
     *   docker login command as a [String].
     */
    private fun Endpoint.toRegistryLoginCommand(): String {
        val credential = when (credential.type) {
            Credential.Type.PASSWORD -> {
                val passwordCredential = requireNotNull(credential.passwordCredential)

                "-u ${passwordCredential.username} -p '${passwordCredential.password}'"
            }
            else -> ""
        }

        return "docker login $address $credential"
    }

    /**
     * Return appropriate container registry setting for Docker daemon depending on the registry's
     * URL scheme.
     *
     * @return
     *   an option [String] to be passed to Docker engine startup.
     */
    private fun Endpoint.toRegistrySecuritySetting(): String {
        val endpoint = URI.create(address)

        return when (endpoint.scheme.toLowerCase()) {
            "http" -> "--insecure-registry ${endpoint.authority}"
            else -> ""
        }
    }

    /**
     * Returns Logging environment variables as a string to be injected at container deployment time
     *
     * @return
     *   environment variables as a List<String> in the format ["key=value", "foo=bar"]
     */
    private fun List<LogManagement>.loggingEnvVariablesSetup(): List<String> {

        if (this.isNotEmpty()) {
            // Get first configuration for now
            val logManagement = this.first()
            // Split address to see if port was configured in the endpoint
            // e.g. https://hostname:9000
            val address: List<String> = logManagement.endpoint.address.split(":")
            val portInt = address.last().toIntOrNull()
            val port: String
            val hostname: String
            when(portInt) {
                null -> {
                    port = ""
                    hostname = logManagement.endpoint.address
                }
                else -> {
                    port = "$portInt"
                    hostname = address.take(address.size-1).joinToString()
                }
            }
            val bearerToken = logManagement.endpoint.credential.tokenCredential.token
            val logInsightAgentId = logManagement.logInsightAgentId

            val loggingEnvVariables: MutableList<String> = mutableListOf()
            // Add consortiumId and replicaId information
            loggingEnvVariables.add("CONSORTIUM_ID=$consortium")
            val replicaUUID : String = UUID(replicaId.high, replicaId.low).toString()
            loggingEnvVariables.add("REPLICA_ID=$replicaUUID")

            // Add logging configurations
            loggingEnvVariables.add("LOG_DESTINATION=${logManagement.destination.name}")
            when(logManagement.destination) {
                LogManagement.Type.LOG_INTELLIGENCE -> {
                    loggingEnvVariables.add("LINT_AUTHORIZATION_BEARER=$bearerToken")
                    loggingEnvVariables.add("LINT_ENDPOINT_URL=$hostname")
                }
                LogManagement.Type.LOG_INSIGHT -> {
                    loggingEnvVariables.add("LOG_INSIGHT_HOST=$hostname")
                    loggingEnvVariables.add("LOG_INSIGHT_PORT=$port")
                    loggingEnvVariables.add("LOG_INSIGHT_USERNAME=" +
                            logManagement.endpoint.credential.passwordCredential.username)
                    loggingEnvVariables.add("LOG_INSIGHT_PASSWORD=" +
                            logManagement.endpoint.credential.passwordCredential.password)
                    loggingEnvVariables.add("LOG_INSIGHT_AGENT_ID=$logInsightAgentId")
                }
            }

            return loggingEnvVariables
        }
        return emptyList()
    }

    /**
     * Retrieve the user-data manifest of the Cloud-Init configuration.
     *
     * @return
     *   user-data manifest as a [String].
     */
    fun userData(): String = script
}

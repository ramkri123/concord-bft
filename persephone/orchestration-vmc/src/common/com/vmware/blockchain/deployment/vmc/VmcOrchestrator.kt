/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.vmc

import com.vmware.blockchain.deployment.vm.InitScript
import com.vmware.blockchain.deployment.model.core.URI
import com.vmware.blockchain.deployment.model.core.UUID
import com.vmware.blockchain.deployment.model.nsx.NatRule
import com.vmware.blockchain.deployment.model.nsx.PublicIP
import com.vmware.blockchain.deployment.model.nsx.Segment
import com.vmware.blockchain.deployment.model.nsx.SegmentSubnet
import com.vmware.blockchain.deployment.model.orchestration.OrchestrationSiteInfo
import com.vmware.blockchain.deployment.model.vsphere.GetDatastoreResponse
import com.vmware.blockchain.deployment.model.vsphere.GetFolderResponse
import com.vmware.blockchain.deployment.model.vsphere.GetNetworkResponse
import com.vmware.blockchain.deployment.model.vsphere.GetResourcePoolResponse
import com.vmware.blockchain.deployment.model.vsphere.LibraryItemDeployRequest
import com.vmware.blockchain.deployment.model.vsphere.LibraryItemDeployResponse
import com.vmware.blockchain.deployment.model.vsphere.LibraryItemDeploymentSpec
import com.vmware.blockchain.deployment.model.vsphere.LibraryItemDeploymentTarget
import com.vmware.blockchain.deployment.model.vsphere.NetworkMapping
import com.vmware.blockchain.deployment.model.vsphere.OvfParameter
import com.vmware.blockchain.deployment.model.vsphere.OvfParameterTypes
import com.vmware.blockchain.deployment.model.vsphere.OvfProperty
import com.vmware.blockchain.deployment.model.vmc.Sddc
import com.vmware.blockchain.deployment.model.vsphere.VirtualMachinePowerResponse
import com.vmware.blockchain.deployment.model.vsphere.VirtualMachinePowerState
import com.vmware.blockchain.deployment.orchestration.Orchestrator
import com.vmware.blockchain.deployment.reactive.Publisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.publish
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * Deployment orchestration driver for VMware Cloud [orchestration site type]
 * [OrchestrationSiteInfo.Type.VMC].
 *
 * @param[vmc]
 *   VMware Cloud API client handle.
 * @param[vSphere]
 *   vSphere API client handle.
 */
class VmcOrchestrator private constructor(
    private val vmc: VmcClient,
    private val vSphere: VSphereClient,
    private val nsx: VmcClient,
    private val context: CoroutineContext = Dispatchers.Default
) : Orchestrator, CoroutineScope {

    /**
     * Global singleton companion to [VmcOrchestrator] that also provides a global-scoped process-
     * wide [CoroutineScope] to launch [VmcOrchestrator]-related coroutines.
     */
    companion object : CoroutineScope {

        /** [CoroutineContext] to launch all coroutines associated with this singleton object. */
        override val coroutineContext: CoroutineContext
            get() = EmptyCoroutineContext

        /**
         * Randomly generate an IPv4 address sub-network constrained within a prefix network range.
         *
         * @param[prefix]
         *   prefix of the network range to create a sub-network within.
         * @param[prefixSubnet]
         *   number of bits used for mask of the prefix network to constrain by.
         * @param[subnet]
         *   number of bits used for mask of the sub-network to generate.
         *
         * @return
         *   generated subnet CIDR as an [Int].
         */
        @JvmStatic
        private fun randomSubnet(prefix: Int, prefixSubnet: Int, subnet: Int): Int {
            return prefix + (Random.nextBits(subnet - prefixSubnet) shl (Int.SIZE_BITS - subnet))
        }

        /**
         * Convert an [Int] to the IPv4 address it represents in canonical format.
         *
         * @param[value]
         *   integer value to convert.
         *
         * @return
         *   canonical IPv4 address as a [String].
         */
        @JvmStatic
        private fun toIPv4Address(value: Int): String {
            val first = (value ushr 24)
            val second = (value and 0x00FF0000) ushr 16
            val third = (value and 0x0000FF00) ushr 8
            val fourth = (value and 0x000000FF)

            return "$first.$second.$third.$fourth"
        }

        /**
         * Get SDDC information associated with a given [VmcClient].
         *
         * @param[vmc]
         *   VMC API client handle.
         *
         * @return
         *   information pertaining to SDDC, as a [Sddc] instance.
         */
        @JvmStatic
        private suspend fun getDataCenterInfo(vmc: VmcClient): Sddc? {
            return vmc
                    .get<Sddc>(
                            Endpoints.VMC_SDDC.interpolate(pathVariables = listOf(
                                    Pair("{org}", vmc.context.organization),
                                    Pair("{sddc}", vmc.context.datacenter)
                            )),
                            contentType = "application/json",
                            headers = emptyList()
                    )
                    .takeIf { it.statusCode() == 200 }
                    ?.let { it.body() }
        }

        /**
         * Create a new [VmcOrchestrator] based on parameter vmc from a given [OrchestrationSiteInfo].
         *
         * @param[site]
         *   orchestration information pertaining to a VMC-based orchestration site.
         * @param[executionContext]
         *   execution context for asynchronous executions triggered by this operation.
         *
         * @return
         *   a [VmcOrchestrator] instance corresponding to the given input parameter.
         */
        @JvmStatic
        fun newOrchestrator(
            site: OrchestrationSiteInfo,
            executionContext: CoroutineContext = Dispatchers.Default
        ): Publisher<VmcOrchestrator> {
            // Precondition.
            require(site.type == OrchestrationSiteInfo.Type.VMC)

            val info = requireNotNull(site.vmc)
            val token = requireNotNull(info.authentication.credential.tokenCredential).token

            // Create new VMC client.
            val vmcContext = VmcClient.Context(
                    endpoint = info.api.address,
                    authenticationEndpoint = info.authentication.address,
                    refreshToken = token,
                    organization = info.organization,
                    datacenter = info.datacenter
            )
            val vmc = VmcClient(vmcContext, ModelSerializer)

            // Asynchronously create the orchestrator instance based on VMC operation results.
            return publish(executionContext) {
                // Use VMC client to obtain VMC SDDC information.
                getDataCenterInfo(vmc)?.apply {
                    // Use VMC SDDC vmc to create NSX client.
                    val nsxUrl = "${resource_config.nsx_api_public_endpoint_url}/sks-nsxt-manager"
                    val nsx = VmcClient.Context(
                            endpoint = URI(nsxUrl),
                            authenticationEndpoint = info.authentication.address,
                            refreshToken = token,
                            organization = info.organization,
                            datacenter = info.datacenter
                    ).let { VmcClient(it, ModelSerializer) } // Proxied NSX client.

                    // Use VMC SDDC vmc to create vSphere client.
                    val vsphere = VSphereClient.Context(
                            endpoint = URI(resource_config.vc_url),
                            username = resource_config.cloud_username,
                            password = resource_config.cloud_password
                    ).let { VSphereClient(it, ModelSerializer) } // vSphere client.

                    // New Orchestrator instance.
                    send(VmcOrchestrator(vmc, vsphere, nsx, executionContext))
                }?: close(RuntimeException("Cannot retrieve site authorization information"))
            }
        }
    }

    /** [CoroutineContext] to launch all coroutines associated with this instance. */
    override val coroutineContext: CoroutineContext
        get() = context + job

    /** Parent [Job] of all coroutines associated with this instance's operation. */
    private val job: Job = Job()

    override fun close() {
        job.cancel()
    }

    override fun createDeployment(
        request: Orchestrator.CreateComputeResourceRequest
    ): Publisher<Orchestrator.ComputeResourceEvent> {
        val clusterId = UUID(request.cluster.high, request.cluster.low)
        val nodeId = UUID(request.node.high, request.node.low)
        return publish(coroutineContext) {
            val getFolder = async { getFolder() }
            val getDatastore = async { getDatastore() }
            val getResourcePool = async { getResourcePool() }
            val ensureControlNetwork = async {
                ensureLogicalNetwork("cgw", "blockchain-control", 0x0A010000, 16, 16)
            }
            val ensureReplicaNetwork = async {
                ensureLogicalNetwork("cgw", "blockchain-data-$clusterId", 0x0AFF0000, 16)
            }
            val getLibraryItem = async { getLibraryItem(request.model.template) }

            // Collect all information and deploy.
            val folder = getFolder.await()
            val datastore = getDatastore.await()
            val resourcePool = getResourcePool.await()
            val controlNetwork = ensureControlNetwork.await()
            val dataNetwork = ensureReplicaNetwork.await()
            val libraryItem = getLibraryItem.await()
            val instance = createVirtualMachine(
                    instanceName = "$clusterId-$nodeId",
                    libraryItem = requireNotNull(libraryItem),
                    datastore = requireNotNull(datastore),
                    resourcePool = requireNotNull(resourcePool),
                    folder = requireNotNull(folder),
                    controlNetwork = controlNetwork,
                    dataNetwork = dataNetwork,
                    initScript = InitScript(request.model)
            )

            // 1. If instance is created, send the created event signal.
            // 2. Then start the instance.
            // 3. If instance is started, send the started event signal.
            // 4. If anything failed, send error signal with the request as the argument.
            instance?.apply {
                        send(Orchestrator.ComputeResourceEvent.Created(URI(this), request.node))
                    }
                    ?.takeIf { ensureVirtualMachinePowerStart(instance) }
                    ?.apply { send(Orchestrator.ComputeResourceEvent.Started(URI(this))) }
                    ?: close(Orchestrator.ResourceCreationFailedException(request))
        }
    }

    override fun deleteDeployment(
        request: Orchestrator.DeleteComputeResourceRequest
    ): Publisher<Orchestrator.ComputeResourceEvent> {
        return publish(coroutineContext) {
            TODO("NOT YET IMPLEMENTED")
        }
    }

    override fun createNetworkAddress(
        request: Orchestrator.CreateNetworkResourceRequest
    ): Publisher<Orchestrator.NetworkResourceEvent> {
        return publish<Orchestrator.NetworkResourceEvent>(coroutineContext) {
            createPublicIP(request.name)
                    ?.ip
                    ?.apply {
                        send(Orchestrator.NetworkResourceEvent.Created(URI(this)))
                    }
                    ?: close(Orchestrator.ResourceCreationFailedException(request))
        }
    }

    override fun deleteNetworkAddress(
        request: Orchestrator.DeleteNetworkResourceRequest
    ): Publisher<Orchestrator.NetworkResourceEvent> {
        return publish(coroutineContext) {
            TODO("NOT YET IMPLEMENTED")
        }
    }

    override fun createNetworkAllocation(
        request: Orchestrator.NetworkAllocationRequest
    ): Publisher<Orchestrator.NetworkAllocationEvent> {
        return publish<Orchestrator.NetworkAllocationEvent>(coroutineContext) {
            send(Orchestrator.NetworkAllocationEvent.Created(request.compute, request.network))
        }
    }

    override fun deleteNetworkAllocation(
        request: Orchestrator.NetworkAllocationRequest
    ): Publisher<Orchestrator.NetworkAllocationEvent> {
        return publish(coroutineContext) {
            TODO("NOT YET IMPLEMENTED")
        }
    }

    /**
     * Get ID of a specified folder based on name and type.
     *
     * @param[name]
     *   name of the folder to look up.
     * @param[type]
     *   type of the folder to constrain the lookup by.
     *
     * @return
     *   ID of the folder as a [String], if found.
     */
    private suspend fun getFolder(
        name: String = "Workloads",
        type: String = "VIRTUAL_MACHINE"
    ): String? {
        return vSphere
                .get<GetFolderResponse>(
                    Endpoints.VSPHERE_FOLDERS
                            .interpolate(parameters = listOf(Pair("filter.type", type),
                                                             Pair("filter.names", name))),
                    contentType = "application/json",
                    headers = emptyList()
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
                ?.takeIf { it.value.isNotEmpty() }
                ?.let { it.value[0] }
                ?.let { it.folder /* ID */ }
    }

    /**
     * Get ID of a specified resource pool based on name.
     *
     * @param[name]
     *   name of the resource pool to look up.
     *
     * @return
     *   ID of the resource pool as a [String], if found.
     */
    private suspend fun getResourcePool(name: String = "Compute-ResourcePool"): String? {
        return vSphere
                .get<GetResourcePoolResponse>(
                    path = Endpoints.VSPHERE_RESOURCE_POOLS
                            .interpolate(parameters = listOf(Pair("filter.names", name))),
                    contentType = "application/json",
                    headers = emptyList()
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
                ?.takeIf { it.value.isNotEmpty() }
                ?.let { it.value[0] }
                ?.let { it.resource_pool /* ID */ }
    }

    /**
     * Get ID of a specified datastore based on name.
     *
     * @param[name]
     *   name of the datastore to look up.
     *
     * @return
     *   ID of the datastore as a [String], if found.
     */
    private suspend fun getDatastore(name: String = "WorkloadDatastore"): String? {
        return vSphere
                .get<GetDatastoreResponse>(
                    path = Endpoints.VSPHERE_DATASTORES
                            .interpolate(parameters = listOf(Pair("filter.names", name))),
                    contentType = "application/json",
                    headers = emptyList()
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
                ?.takeIf { it.value.isNotEmpty() }
                ?.let { it.value[0] }
                ?.let { it.datastore /* ID */ }
    }

    /**
     * Get information regarding a specified tier-1 network segment based on name.
     *
     * @param[tier1]
     *   identifier of the tier-1 network.
     * @param[name]
     *   name of the network segment to look up.
     *
     * @return
     *   information regarding the network as a [Segment], if found.
     */
    private suspend fun getNetworkSegment(tier1: String, name: String): Segment? {
        return nsx
                .get<Segment>(
                        Endpoints.NSX_NETWORK_SEGMENT
                                .interpolate(pathVariables = listOf(Pair("{tier1}", tier1),
                                                                    Pair("{segment}", name))),
                        contentType = "application/json",
                        headers = emptyList()
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
    }

    /**
     * Create a routed NSX logical network segment with the given parameters.
     *
     * @param[tier1]
     *   tier-1 network to create the logical network segment under.
     * @param[name]
     *   name of the network segment to create.
     * @param[prefix]
     *   network prefix range to create the network segment under.
     * @param[prefixSubnet]
     *   subnet size for the network prefix range.
     * @param[subnetSize]
     *   subnet size for the network segment itself.
     *
     * @return
     *   ID of the network segment as a [String], if created.
     */
    private suspend fun createNetworkSegment(
        tier1: String,
        name: String,
        prefix: Int,
        prefixSubnet: Int,
        subnetSize: Int = 28
    ): String? {
        // Generate a network model based on a randomly generated subnet.
        // Note (current implementation choices):
        // - Primary address is set to the first address in the subnet.
        // - DHCP pool can assign addresses from second address to subnet-broadcast (max) - 1.
        val subnet = randomSubnet(prefix, prefixSubnet, subnetSize)
        val subnetMax = subnet + (1 shl (Int.SIZE_BITS - subnetSize)) - 1
        val segmentSubnet = SegmentSubnet(
                gateway_address = "${toIPv4Address(subnet + 1)}/$subnetSize",
                dhcp_ranges = listOf("${toIPv4Address(subnet + 2)}-${toIPv4Address(subnetMax - 1)}")
        )
        val segment = Segment(subnets = listOf(segmentSubnet))

        val response = nsx
                .patch<Segment, Segment>(
                        Endpoints.NSX_NETWORK_SEGMENT
                                .interpolate(pathVariables = listOf(Pair("{tier1}", tier1),
                                                                    Pair("{segment}", name))),
                        contentType = "application/json",
                        headers = emptyList(),
                        body = segment
                )

        return when (response.statusCode()) {
            200 -> {
                // The API does not return the resource ID from vSphere's perspective, so we have to
                // follow up w/ another GET.
                //
                // We need to be able to retrieve the ID by name. If not found, just return no
                // result and let caller try again.
                //
                // Note:
                // Given that getNetwork() and intent creation are via 2 separate endpoints, it is
                // possible that vSphere does not yet have the created entity even though the NSX
                // network intent has been created. In this case, status 200 will still result in
                // entity not found (i.e. null).
                getNetwork(name)
            }
            else -> null
        }
    }

    /**
     * Get ID of a specified logical network port-group based on name.
     *
     * @param[name]
     *   name of the network to look up.
     * @param[type]
     *   type of the network to constrain the lookup by.
     *
     * @return
     *   ID of the network as a [String], if found.
     */
    private suspend fun getNetwork(name: String, type: String = "OPAQUE_NETWORK"): String? {
        return vSphere
                .get<GetNetworkResponse>(
                    path = Endpoints.VSPHERE_NETWORKS
                            .interpolate(parameters = listOf(Pair("filter.types", type),
                                                             Pair("filter.names", name))),
                    contentType = "application/json",
                    headers = emptyList()
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
                ?.takeIf { it.value.isNotEmpty() }
                ?.let { it.value[0] }
                ?.let { it.network /* ID */ }
    }

    /**
     * Ensure the existence of a named logical network by creating it according to the given
     * parameters if the network is not found.
     *
     * Note: This suspendable method does not terminate until the network exists. If necessary, it
     * is the caller's responsibility to constrain the call within a cancellable coroutine scope in
     * order to properly terminate and reclaim any system resources (background thread) engendered
     * by an invocation of this method.
     *
     * @param[attachedGateway]
     *   ID of the gateway / up-link to attach the logical network.
     * @param[name]
     *   unique name of the logical network within the target SDDC.
     * @param[prefix]
     *   prefix address of the network range to create the sub-network CIDR within.
     * @param[prefixSubnet]
     *   prefix subnet (size) of the network range to create the sub-network CIDR within.
     * @param[subnetSize]
     *   size of the logical network subnet.
     *
     * @return
     *   ID of the logical network as a [String].
     */
    private suspend fun ensureLogicalNetwork(
        attachedGateway: String,
        name: String,
        prefix: Int,
        prefixSubnet: Int,
        subnetSize: Int = 28
    ): String {
        var result: String? = null
        while (result == null) {
            // Create the network segment and resolve its vSphere name. Due to realization delays,
            // the create() call may not immediately yield any result. In such cases, let the loop
            // take care of eventually getting a network segment that matches the desired name.
            result = getNetwork(name)
                    ?: createNetworkSegment(attachedGateway, name, prefix, prefixSubnet, subnetSize)

            if (result == null) {
                // Do not engage next iteration immediately.
                delay(500) // 500ms.
            }
        }
        return result
    }

    /**
     * Get ID of the specified content library item based on name.
     *
     * @param[name]
     *   name of the library item.
     *
     * @return
     *   ID of the library item as a [String], if found.
     */
    private suspend fun getLibraryItem(name: String): String? {
        // TODO: Finish integration work with Content Library API.
        // return "f4ccb861-6716-4e08-b58d-323977f13aab"
        return "dad738c1-43d8-4138-8fcc-52a7d86ff52b"
    }

    /**
     * Create a housing instance based on the specified parameters.
     */
    private suspend fun createVirtualMachine(
        instanceName: String,
        libraryItem: String,
        datastore: String,
        resourcePool: String,
        folder: String,
        controlNetwork: String,
        dataNetwork: String,
        initScript: InitScript
    ): String? {
        val deployRequest = LibraryItemDeployRequest(
                LibraryItemDeploymentSpec(
                        name = instanceName,
                        accept_all_EULA = true,
                        default_datastore_id = datastore,
                        network_mappings = listOf(
                                NetworkMapping("control-network", controlNetwork),
                                NetworkMapping("data-network", dataNetwork)
                        ),
                        additional_parameters = listOf(
                                OvfParameter(
                                        `@class` = OvfParameterTypes.PROPERTY_PARAMS.classValue,
                                        type = OvfParameterTypes.PROPERTY_PARAMS.type,
                                        properties = listOf(
                                                OvfProperty("instance-id", instanceName),
                                                OvfProperty("hostname", "replica"),
                                                OvfProperty("user-data", String(initScript.base64()))
                                        )
                                )
                        )
                ),
                LibraryItemDeploymentTarget(
                        resource_pool_id = resourcePool,
                        folder_id = folder
                )
        )

        val response = vSphere
                .post<LibraryItemDeployRequest, LibraryItemDeployResponse>(
                    path = Endpoints.VSPHERE_OVF_LIBRARY_ITEM
                            .interpolate(
                                     pathVariables = listOf(Pair("{library_item}", libraryItem)),
                                     parameters = listOf(Pair("~action", "deploy"))),
                    contentType = "application/json",
                    headers = emptyList(),
                    body = deployRequest
                )
        return when (response.statusCode()) {
            200 -> {
                response.body()
                        ?.value
                        ?.takeIf { it.succeeded }
                        ?.resource_id
                        ?.id
            }
            else -> null
        }
    }

    /**
     * Power on a virtual machine specified by the given parameter.
     *
     * @param[name]
     *   identifier of the virtual machine.
     *
     * @return
     *   `true` if power-on operation is successfully executed for the specified virtual machine,
     *   `false` otherwise.
     */
    private suspend fun powerOnVirtualMachine(name: String): Boolean {
        return vSphere
                .post<Unit, Unit>(
                        path = Endpoints.VSPHERE_VM_POWER_START
                                .interpolate(pathVariables = listOf(Pair("{vm}", name))),
                        contentType = "application/json",
                        headers = emptyList(),
                        body = null
                )
                .let { it.statusCode() == 200 }
    }

    /**
     * Get the power state of the virtual machine.
     *
     * @param[name]
     *   identifier of the virtual machine.
     *
     * @return
     *   the power state of the virtual machine expressed as [VirtualMachinePowerState], `null` if
     *   the operation cannot be executed or if the virtual machine does not exist.
     */
    private suspend fun getVirtualMachinePower(name: String): VirtualMachinePowerState? {
        return vSphere
                .get<VirtualMachinePowerResponse>(
                        path = Endpoints.VSPHERE_VM_POWER
                                .interpolate(pathVariables = listOf(Pair("{vm}", name))),
                        contentType = "application/json",
                        headers = emptyList()
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
                ?.let { it.value }
                ?.let { it.state }
    }

    /**
     * Ensure the power-on state of the virtual machine specified by the given parameter.
     *
     * @param[name]
     *   identifier of the virtual machine.
     *
     * @return
     *   `true` if the power state of the virtual machine is on at some point during the execution
     *   of the function, `false` otherwise.
     */
    private suspend fun ensureVirtualMachinePowerStart(name: String): Boolean {
        var confirmed = false
        var iterating = true
        while (iterating) {
            // The typical case.
            powerOnVirtualMachine(name)
            when (getVirtualMachinePower(name)) {
                VirtualMachinePowerState.POWERED_OFF, VirtualMachinePowerState.SUSPEND -> {
                    powerOnVirtualMachine(name)

                    // Do not engage next iteration immediately.
                    delay(500) // 500ms.
                }
                VirtualMachinePowerState.POWERED_ON -> {
                    iterating = false
                    confirmed = true
                }
                else -> {
                    // If the VM doesn't exist or power-state cannot be retrieved.
                    iterating = false
                    confirmed = false
                }
            }
        }

        return confirmed
    }

    /**
     * Allocate a new public IP address for use, with a given name as the identifier.
     *
     * @param[name]
     *   identifier of the IP address resource.
     *
     * @return
     *   allocated IP address resource as a instance of [PublicIP], if created, `null` otherwise.
     */
    private suspend fun createPublicIP(name: String): PublicIP? {
        return nsx
                .put<PublicIP, PublicIP>(
                        path = Endpoints.VMC_PUBLIC_IP
                                .interpolate(pathVariables = listOf("{ip_id}" to name)),
                        contentType = "application/json",
                        headers = emptyList(),
                        body = PublicIP(id = name, display_name = name)
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body() }
    }

    /**
     * Create a NAT rule based on the given parameters.
     *
     * @param[tier1]
     *   tier-1 network that the NAT region is under for NAT rule addition.
     * @param[nat]
     *   NAT region to add rule to.
     * @param[name]
     *   identifier to assign to the NAT rule.
     * @param[action]
     *   type of NAT action.
     * @param[sourceNetwork]
     *   source address(es) to translate for SNAT and REFLEXIVE rules.
     * @param[destinationNetwork]
     *   destination address(es) to translate for DNAT and REFLEXIVE rules.
     * @param[translatedNetwork]
     *   network address to apply translation into.
     * @param[translatedPorts]
     *   network ports to apply translation.
     */
    private suspend fun createNatRule(
        tier1: String,
        nat: String,
        name: String,
        action: NatRule.Action,
        sourceNetwork: String,
        destinationNetwork: String,
        translatedNetwork: String,
        translatedPorts: String
    ): NatRule? {
        return nsx
                .patch<NatRule, NatRule>(
                        path = Endpoints.NSX_NAT_RULE
                                .interpolate(pathVariables = listOf(
                                        "{network}" to tier1,
                                        "{nat}" to nat,
                                        "{nat_rule}" to name
                                )),
                        contentType = "application/json",
                        headers = emptyList(),
                        body = NatRule(
                                action = action,
                                source_network = sourceNetwork,
                                destination_network = destinationNetwork,
                                translated_network = translatedNetwork,
                                translated_ports = translatedPorts
                        )
                )
                .takeIf { it.statusCode() == 200 }
                ?.let { it.body()}
    }
}

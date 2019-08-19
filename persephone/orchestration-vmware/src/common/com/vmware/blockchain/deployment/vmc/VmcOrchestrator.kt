/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.vmc

import com.vmware.blockchain.deployment.logging.info
import com.vmware.blockchain.deployment.logging.logger
import com.vmware.blockchain.deployment.model.Address
import com.vmware.blockchain.deployment.model.AllocateAddressRequest
import com.vmware.blockchain.deployment.model.AllocateAddressResponse
import com.vmware.blockchain.deployment.model.IPAllocationServiceStub
import com.vmware.blockchain.deployment.model.IPv4Network
import com.vmware.blockchain.deployment.model.MessageHeader
import com.vmware.blockchain.deployment.model.OrchestrationSiteInfo
import com.vmware.blockchain.deployment.model.ReleaseAddressRequest
import com.vmware.blockchain.deployment.model.ReleaseAddressResponse
import com.vmware.blockchain.deployment.model.VmcOrchestrationSiteInfo
import com.vmware.blockchain.deployment.model.core.URI
import com.vmware.blockchain.deployment.model.core.UUID
import com.vmware.blockchain.deployment.model.nsx.NatRule
import com.vmware.blockchain.deployment.model.nsx.PublicIP
import com.vmware.blockchain.deployment.model.vsphere.VirtualMachineGuestIdentityInfo
import com.vmware.blockchain.deployment.orchestration.Orchestrator
import com.vmware.blockchain.deployment.orchestration.toIPv4Address
import com.vmware.blockchain.deployment.orchestration.vmware.newClientRpcChannel
import com.vmware.blockchain.deployment.reactive.Publisher
import com.vmware.blockchain.deployment.vm.CloudInitConfiguration
import com.vmware.blockchain.deployment.vsphere.VSphereClient
import com.vmware.blockchain.deployment.vsphere.VSphereHttpClient
import com.vmware.blockchain.deployment.vsphere.VSphereModelSerializer
import com.vmware.blockchain.grpc.kotlinx.serialization.ChannelStreamObserver
import io.grpc.CallOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.publish
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/**
 * Deployment orchestration driver for VMware Cloud [orchestration site type]
 * [OrchestrationSiteInfo.Type.VMC].
 *
 * @property[info]
 *   environment context describing the target orchestration site environment.
 * @property[vmc]
 *   VMware Cloud API client handle.
 * @property[context]
 *   coroutine execution context for coroutines launched by this instance.
 */
class VmcOrchestrator(
    private val info: VmcOrchestrationSiteInfo,
    private val vmc: VmcClient,
    private val context: CoroutineContext = Dispatchers.Default
) : Orchestrator, CoroutineScope {

    /** vSphere Client to utilize to exact orchestration actions. */
    private lateinit var vSphere: VSphereClient

    /** vSphere Client to utilize to exact orchestration actions. */
    private lateinit var nsx: VmcClient

    /**
     * Mappings of [IPv4Network] name to service's client stub. (name corresponds to the specified
     * network names in `vsphere` property within [info].
     */
    private lateinit var networkAddressAllocationServers: Map<String, IPAllocationServiceStub>

    /**
     * Global singleton companion to [VmcOrchestrator] that also provides a global-scoped process-
     * wide [CoroutineScope] to launch [VmcOrchestrator]-related coroutines.
     */
    companion object {

        /** Default internal retry interval for SDDC operations. */
        const val OPERATION_RETRY_INTERVAL_MILLIS = 500L

        /** Default maximum orchestrator operation timeout value. */
        const val ORCHESTRATOR_TIMEOUT_MILLIS = 60000L * 10

        /** Default IPAM resource name prefix. */
        const val IPAM_RESOURCE_NAME_PREFIX = "blocks/"
    }

    /** Logging instance. */
    private val log by logger()

    /** [CoroutineContext] to launch all coroutines associated with this instance. */
    override val coroutineContext: CoroutineContext
        get() = context + job

    /** Parent [Job] of all coroutines associated with this instance's operation. */
    private val job: Job = SupervisorJob()

    override fun initialize(): Publisher<Any> {
        return publish {
            vmc.getDataCenterInfo()?.apply {
                // Use VMC SDDC info to create NSX client.
                nsx = VmcHttpClient.Context(
                        endpoint = URI(resource_config.nsx_api_public_endpoint_url),
                        authenticationEndpoint = URI.create(info.authentication.address),
                        refreshToken = info.authentication.credential.tokenCredential.token,
                        organization = info.organization,
                        datacenter = info.datacenter
                ).let { VmcClient(VmcHttpClient(it, VmcModelSerializer)) }

                // Use VMC SDDC info to create vSphere client.
                vSphere = VSphereHttpClient.Context(
                        endpoint = URI(resource_config.vc_url),
                        username = resource_config.cloud_username,
                        password = resource_config.cloud_password
                ).let { VSphereClient(VSphereHttpClient(it, VSphereModelSerializer)) }

                // There is only the default network right now. When more networks are introduced
                // (to be allocated for provisioned nodes as network interfaces), the mapping will
                // contain more than one entry.
                networkAddressAllocationServers = mapOf(
                        info.vsphere.network.let {
                            it.name to IPAllocationServiceStub(
                                    it.allocationServer.newClientRpcChannel(),
                                    CallOptions.DEFAULT
                            )
                        }
                )
            }
        }
    }

    override fun close() {
        job.cancel()
    }

    override fun createDeployment(
        request: Orchestrator.CreateComputeResourceRequest
    ): Publisher<Orchestrator.ComputeResourceEvent> {
        val compute = info.vsphere.resourcePool
        val storage = info.vsphere.datastore
        val network = info.vsphere.network

        return publish(coroutineContext) {
            withTimeout(ORCHESTRATOR_TIMEOUT_MILLIS) {
                try {
                    val clusterId = UUID(request.cluster.high, request.cluster.low)
                    val nodeId = UUID(request.node.high, request.node.low)
                    val subnetMask = ((1 shl (32 - network.subnet)) - 1).inv()

                    val getFolder = async { vSphere.getFolder(name = info.vsphere.folder) }
                    val getDatastore = async { vSphere.getDatastore(name = storage) }
                    val getResourcePool = async { vSphere.getResourcePool(name = compute) }
                    val ensureControlNetwork = async {
                        ensureLogicalNetwork(
                                "cgw",
                                network.name,
                                network.gateway and subnetMask,
                                network.subnet,
                                network.subnet
                        )
                    }
                    val ensureReplicaNetwork = async {
                        ensureLogicalNetwork("cgw", network.name, 0x0AFF0000, 16)
                    }
                    val getLibraryItem = async { vSphere.getLibraryItem(request.model.template) }

                    // Collect all information and deploy.
                    val folder = getFolder.await()
                    val datastore = getDatastore.await()
                    val resourcePool = getResourcePool.await()
                    val controlNetwork = ensureControlNetwork.await()
                    val dataNetwork = ensureReplicaNetwork.await()
                    val libraryItem = getLibraryItem.await()
                    val instance = vSphere.createVirtualMachine(
                            name = "$clusterId-$nodeId",
                            libraryItem = requireNotNull(libraryItem),
                            datastore = requireNotNull(datastore),
                            resourcePool = requireNotNull(resourcePool),
                            folder = requireNotNull(folder),
                            networks = listOf(
                                    "blockchain-network" to controlNetwork
                            ),
                            cloudInit = CloudInitConfiguration(
                                    info.containerRegistry,
                                    request.model,
                                    request.genesis,
                                    request.privateNetworkAddress,
                                    network.gateway.toIPv4Address(),
                                    network.subnet,
                                    request.cluster,
                                    request.concordId,
                                    request.configurationSessionIdentifier,
                                    request.configServiceEndpoint
                            )
                    )

                    // 1. If instance is created, send the created event signal.
                    // 2. Then start the instance.
                    // 3. If instance is started, send the started event signal.
                    // 4. If anything failed, send error signal with the request as the argument.
                    instance?.toComputeResource()
                            ?.apply {
                                send(Orchestrator.ComputeResourceEvent.Created(this, request.node))
                            }
                            ?.takeIf { vSphere.ensureVirtualMachinePowerStart(instance) }
                            ?.apply { send(Orchestrator.ComputeResourceEvent.Started(this)) }
                            ?: close(Orchestrator.ResourceCreationFailedException(request))
                } catch (error: CancellationException) {
                    close(Orchestrator.ResourceCreationFailedException(request))
                }
            }
        }
    }

    override fun deleteDeployment(
        request: Orchestrator.DeleteComputeResourceRequest
    ): Publisher<Orchestrator.ComputeResourceEvent> {
        @Suppress("DuplicatedCode")
        return publish<Orchestrator.ComputeResourceEvent>(coroutineContext) {
            withTimeout(ORCHESTRATOR_TIMEOUT_MILLIS) {
                try {
                    // Retrieve only the last portion of the URI to get the VM ID.
                    val id = request.resource.path.substringAfterLast("/")

                    // Ensure that the VM is powered off.
                    vSphere.ensureVirtualMachinePowerStop(id).takeIf { it }
                            ?.run { vSphere.deleteVirtualMachine(id) }?.takeIf { it }
                            ?.run {
                                send(Orchestrator.ComputeResourceEvent.Deleted(request.resource))
                            }
                            ?: close(Orchestrator.ResourceDeletionFailedException(request))
                } catch (error: CancellationException) {
                    close(Orchestrator.ResourceDeletionFailedException(request))
                }
            }
        }
    }

    override fun createNetworkAddress(
        request: Orchestrator.CreateNetworkResourceRequest
    ): Publisher<Orchestrator.NetworkResourceEvent> {
        val network = info.vsphere.network

        return publish<Orchestrator.NetworkResourceEvent>(coroutineContext) {
            withTimeout(ORCHESTRATOR_TIMEOUT_MILLIS) {
                try {
                    val privateIpAddress = allocatedPrivateIP(network)
                    privateIpAddress
                            ?.apply {
                                log.info { "Created private IP($privateIpAddress)" }

                                send(Orchestrator.NetworkResourceEvent.Created(
                                        URI.create("//${network.allocationServer.address}/$name"),
                                        request.name,
                                        privateIpAddress.value.toIPv4Address(),
                                        false))
                            }
                            ?: close(Orchestrator.ResourceCreationFailedException(request))

                    val publicIpAddress = nsx.createPublicIP(request.name)
                    publicIpAddress?.takeIf { it.ip != null }
                            ?.apply {
                                log.info { "Created public IP(${publicIpAddress.ip})" }

                                send(Orchestrator.NetworkResourceEvent.Created(
                                        this.toNetworkResource(),
                                        request.name,
                                        ip!!,
                                        true))
                                close()
                            }
                            ?: close(Orchestrator.ResourceCreationFailedException(request))

                } catch (error: CancellationException) {
                    log.info { "Network address creation request was cancelled" }

                    close(Orchestrator.ResourceCreationFailedException(request))
                } catch (error: Throwable) {
                    log.error("Unexpected error(${error.message})", error)

                    close(Orchestrator.ResourceCreationFailedException(request))
                }
            }
        }
    }

    override fun deleteNetworkAddress(
        request: Orchestrator.DeleteNetworkResourceRequest
    ): Publisher<Orchestrator.NetworkResourceEvent> {
        val network = info.vsphere.network

        return publish<Orchestrator.NetworkResourceEvent>(coroutineContext) {
            withTimeout(ORCHESTRATOR_TIMEOUT_MILLIS) {
                try {
                    // FIXME:
                    //   The orchestrator should group all the resource providers via its authority.
                    //   During resource event emission, the authority
                    //   (i.e. <scheme>://<authority>/...)
                    //   should determine "how" and "who" to contact to release the resource, be it
                    //   VC, nsx, or IPAM.
                    if (request.resource.scheme == null) {
                        releasePrivateIP(network, request.resource).takeIf { it }
                                ?.run {
                                    send(Orchestrator.NetworkResourceEvent.Deleted(request.resource))
                                }
                                ?: close(Orchestrator.ResourceDeletionFailedException(request))
                    } else {
                        // Issue DELETE against the IP resource via its URI.
                        nsx.deleteResource(request.resource).takeIf { it }
                                ?.run {
                                    send(Orchestrator.NetworkResourceEvent.Deleted(request.resource))
                                }
                                ?: close(Orchestrator.ResourceDeletionFailedException(request))
                    }
                } catch (error: CancellationException) {
                        close(Orchestrator.ResourceDeletionFailedException(request))
                }
            }
        }
    }

    override fun createNetworkAllocation(
        request: Orchestrator.CreateNetworkAllocationRequest
    ): Publisher<Orchestrator.NetworkAllocationEvent> {
        return publish<Orchestrator.NetworkAllocationEvent>(coroutineContext) {
            withTimeout(ORCHESTRATOR_TIMEOUT_MILLIS) {
                try {
                    // Retrieve only the last portion of the URI to get the VM ID.
                    val computeId = request.compute.path.substringAfterLast("/")

                    // Obtain current private IP.
                    // Note: This can take a while as guest needs to boot and start vmware-tools
                    // service and also have the service pick up and report network stack
                    // information back to SDDC.
                    //
                    // TODO: This is not the eventual workflow with agent in the picture.
                    // Current workflow involves polling the deployed entities for its current
                    // status, which does not scale well.
                    // The scalable approach involves the agent calling back to fleet management,
                    // which can *then* trigger the createNetworkAllocation workflow.
                    //
                    // Longer retry frequency to take into account of potentially long wait.
                    val retryFrequency = 5000L
                    var info: VirtualMachineGuestIdentityInfo? = null
                    while (info == null) {
                        delay(retryFrequency)
                        info = vSphere.getVirtualMachineGuestIdentity(computeId)
                    }
                    val privateIP = info.ip_address

                    // Obtain the public IP address from the IP resource.
                    val networkId = request.network.path.substringAfterLast("/")
                    val publicIP = nsx.getPublicIP(networkId)?.ip

                    // Setup the NAT rule.
                    nsx.createNatRule(name = request.name,
                                  action = NatRule.Action.REFLEXIVE,
                                  sourceNetwork = checkNotNull(privateIP),
                                  translatedNetwork = checkNotNull(publicIP))
                            ?.toNetworkAllocationResource()
                            ?.apply {
                                send(Orchestrator.NetworkAllocationEvent.Created(
                                        this,
                                        request.name,
                                        request.compute,
                                        request.network
                                ))
                            }
                            ?: close(Orchestrator.ResourceCreationFailedException(request))
                } catch (error: CancellationException) {
                    close(Orchestrator.ResourceCreationFailedException(request))
                }
            }
        }
    }

    override fun deleteNetworkAllocation(
        request: Orchestrator.DeleteNetworkAllocationRequest
    ): Publisher<Orchestrator.NetworkAllocationEvent> {
        return publish<Orchestrator.NetworkAllocationEvent>(coroutineContext) {
            withTimeout(ORCHESTRATOR_TIMEOUT_MILLIS) {
                try {
                    nsx.deleteResource(request.resource).takeIf { it }
                            ?.run {
                                send(Orchestrator.NetworkAllocationEvent.Deleted(request.resource))
                            }
                            ?: close(Orchestrator.ResourceDeletionFailedException(request))
                } catch (error: CancellationException) {
                    close(Orchestrator.ResourceDeletionFailedException(request))
                }
            }
        }
    }

    /**
     * Represent the [String] as a compute resource, as known by this [Orchestrator].
     *
     * @return
     *   compute resource as a [URI] instance.
     */
    private fun String.toComputeResource(): URI {
        return vSphere.resolveVirtualMachineIdentifier(this)
    }

    /**
     * Represent the [PublicIP] as a network resource [URI], as known by this [Orchestrator].
     *
     * @return
     *   network resource as a [URI] instance.
     */
    private fun PublicIP.toNetworkResource(): URI {
        return nsx.resolvePublicIPIdentifier(checkNotNull(id))
    }

    /**
     * Represent the [NatRule] as an allocation resource [URI], as known by this [Orchestrator].
     *
     * @return
     *   allocation resource as a [URI] instance.
     */
    private fun NatRule.toNetworkAllocationResource(): URI {
        return nsx.resolveNsxResourceIdentifier(checkNotNull(path))
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
     * @param[tier1]
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
        tier1: String = "cgw",
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
            result = vSphere.getNetwork(name)
                    ?: nsx.createNetworkSegment(tier1, name, prefix, prefixSubnet, subnetSize)
                            // The API does not return the resource ID from vSphere's perspective,
                            // so we have to follow up w/ another GET.
                            //
                            // We need to be able to retrieve the ID by name. If not found, just
                            // return no result and let caller try again.
                            //
                            // Note:
                            // Given that getNetwork() and intent creation are via 2 separate
                            // endpoints, it is possible that vSphere does not yet have the created
                            // entity even though the NSX network intent has been created. In this
                            // case, status 200 will still result in entity not found (i.e. null).
                            ?.let { vSphere.getNetwork(it) }

            if (result == null) {
                // Do not engage next iteration immediately.
                delay(OPERATION_RETRY_INTERVAL_MILLIS)
            }
        }
        return result
    }

    /**
     * Allocate a new private IP address for use from IP allocation service.
     *
     * @param[network]
     *   network to allocate IP address resource from.
     *
     * @return
     *   allocated IP address resource as a instance of [Address], if created, `null` otherwise.
     */
    @Suppress("DuplicatedCode")
    private suspend fun allocatedPrivateIP(network: IPv4Network): Address? {
        val requestAllocateAddress = AllocateAddressRequest(
                header = MessageHeader(),
                parent = IPAM_RESOURCE_NAME_PREFIX + info.datacenter + "-" + network.name
        )

        val observer = ChannelStreamObserver<AllocateAddressResponse>(1)
        val ipAllocationService = requireNotNull(networkAddressAllocationServers[network.name])
        ipAllocationService.allocateAddress(requestAllocateAddress, observer)
        val response = observer.asReceiveChannel().receive()

        return response
                .takeIf { it.status == AllocateAddressResponse.Status.OK }
                ?.let { it.address }
    }

    /**
     * Release an IP address resource from a given [IPv4Network].
     *
     * @param[network]
     *   network to release IP address resource from.
     * @param[resource]
     *   URI of the resource to be released.
     *
     * @return
     *   `true` if resource is successfully released, `false` otherwise.
     */
    @Suppress("DuplicatedCode")
    private suspend fun releasePrivateIP(network: IPv4Network, resource: URI): Boolean {
        val observer = ChannelStreamObserver<ReleaseAddressResponse>(1)
        val requestAllocateAddress = ReleaseAddressRequest(
                header = MessageHeader(),
                name = resource.path.removePrefix("/")
        )

        // IP allocation service client.
        val ipAllocationService = requireNotNull(networkAddressAllocationServers[network.name])
        ipAllocationService.releaseAddress(requestAllocateAddress, observer)

        return observer.asReceiveChannel().receive().status == ReleaseAddressResponse.Status.OK
    }
}

/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.orchestration

import com.vmware.blockchain.deployment.model.ConcordClusterIdentifier
import com.vmware.blockchain.deployment.model.ConcordModelSpecification
import com.vmware.blockchain.deployment.model.ConcordNodeIdentifier
import com.vmware.blockchain.deployment.model.core.URI
import com.vmware.blockchain.deployment.model.ethereum.Genesis
import com.vmware.blockchain.deployment.reactive.Publisher

/**
 * Asynchronous deployment orchestration library interface.
 *
 * Specific implementation may employ different strategies to satisfy the interface operations (e.g.
 * stateful, staged executions, cached resources, pre-allocations, etc).
 */
interface Orchestrator {

    /**
     * Error denoting the condition that a resource creation requisition cannot be satisfied.
     *
     * @param[request]
     *   resource requisition request.
     */
    data class ResourceCreationFailedException(
        val request: Any
    ) : RuntimeException("Failed to create resource")

    /**
     * Error denoting the condition that a resource deletion requisition cannot be satisfied.
     *
     * @param[request]
     *   resource requisition request.
     */
    data class ResourceDeletionFailedException(
        val request: Any
    ) : RuntimeException("Failed to delete resource")

    /**
     * Compute resource deployment creation request specification.
     *
     * @param[cluster]
     *   identifier of the cluster the deployed resource belongs to.
     * @param[node]
     *   identifier of the member node.
     * @param[model]
     *   metadata specification of versioned Concord model template to deploy.
     */
    data class CreateComputeResourceRequest(
        val cluster: ConcordClusterIdentifier,
        val node: ConcordNodeIdentifier,
        val model: ConcordModelSpecification,
        val genesis: Genesis,

        // FIXME: THIS IS TEMPORARY UNTIL CONFIGURATION IS FETCHED BY PERSEPHONE-AGENT.
        val configuration: String = "",
        val privateNetworkAddress: String
    )

    /**
     * Compute resource deployment deletion request specification.
     *
     * @param[resource]
     *   deployment resource to be deleted.
     */
    data class DeleteComputeResourceRequest(val resource: URI)

    /**
     * Generic interface type denoting a deployment event.
     */
    interface OrchestrationEvent

    /**
     * Events corresponding to the execution of a deployment session.
     */
    sealed class ComputeResourceEvent : OrchestrationEvent {
        abstract val resource: URI

        data class Created(
            override val resource: URI,
            val node: ConcordNodeIdentifier
        ) : ComputeResourceEvent()
        data class Started(override val resource: URI) : ComputeResourceEvent()
        data class Deleted(override val resource: URI) : ComputeResourceEvent()
    }

    /**
     * Network resource provisioning request specification.
     *
     * @param[name]
     *   name of the network resource to create, for [Orchestrator] backends that associate network
     *   resource with additional naming identifiers or surrogate identifiers to each provisioned
     *   network resource.
     * @param[public]
     *   whether network resource must be externally reachable outside of the orchestration site.
     */
    data class CreateNetworkResourceRequest(
        val name: String,
        val public: Boolean
    )

    /**
     * Network resource de-provisioning request specification.
     *
     * @param[resource]
     *   network address resource to be de-provisioned.
     */
    data class DeleteNetworkResourceRequest(val resource: URI)

    /**
     * Events corresponding to the execution of a network address requisition workflow.
     */
    sealed class NetworkResourceEvent : OrchestrationEvent {
        abstract val resource: URI

        data class Created(
            override val resource: URI,
            val name: String,
            val address: String,
            val public: Boolean
        ) : NetworkResourceEvent()
        data class Deleted(override val resource: URI) : NetworkResourceEvent()
    }

    /**
     * Allocation request to assign a network resource to a given deployment.
     *
     * @param[compute]
     *   compute resource to allocate network resource to.
     * @param[network]
     *   network resource to be assigned.
     */
    data class CreateNetworkAllocationRequest(val compute: URI, val network: URI)

    /**
     * Network allocation de-provisioning request specification.
     *
     * @param[resource]
     *   network allocation resource to be de-provisioned.
     */
    data class DeleteNetworkAllocationRequest(val resource: URI)

    /**
     * Events corresponding to the execution of a network allocation workflow.
     */
    sealed class NetworkAllocationEvent : OrchestrationEvent {
        abstract val resource: URI

        data class Created(
            override val resource: URI,
            val compute: URI,
            val network: URI
        ) : NetworkAllocationEvent()
        data class Deleted(override val resource: URI) : NetworkAllocationEvent()
    }

    /**
     * Shutdown the [Orchestrator] instance and closes all resources.
     */
    fun close()

    /**
     * Create a Concord deployment based on a given [CreateComputeResourceRequest].
     *
     * @param[request]
     *   deployment creation request specification.
     *
     * @return
     *   a [Publisher] of [ComputeResourceEvent] corresponding to side-effects engendered by the
     *   request.
     */
    fun createDeployment(request: CreateComputeResourceRequest): Publisher<ComputeResourceEvent>

    /**
     * Delete a Concord deployment based on a given [DeleteComputeResourceRequest].
     *
     * @param[request]
     *   deployment deletion request specification.
     *
     * @return
     *   a [Publisher] of [ComputeResourceEvent] corresponding to side-effects engendered by the
     *   request.
     */
    fun deleteDeployment(request: DeleteComputeResourceRequest): Publisher<ComputeResourceEvent>

    /**
     * Create a reachable network address based on a given [CreateNetworkResourceRequest].
     *
     * @param[request]
     *   network address creation request specification.
     *
     * @return
     *   a [Publisher] of [NetworkResourceEvent] corresponding to side-effects engendered by the
     *   request.
     */
    fun createNetworkAddress(request: CreateNetworkResourceRequest): Publisher<NetworkResourceEvent>

    /**
     * Delete a reachable network address based on a given [DeleteNetworkResourceRequest].
     *
     * @param[request]
     *   network address creation request specification.
     *
     * @return
     *   a [Publisher] of [NetworkResourceEvent] corresponding to side-effects engendered by the
     *   request.
     */
    fun deleteNetworkAddress(request: DeleteNetworkResourceRequest): Publisher<NetworkResourceEvent>

    /**
     * Allocate a network address to a deployed compute resource.
     *
     * @param[request]
     *   network address allocation request specification.
     *
     * @return
     *   a [Publisher] of [NetworkAllocationEvent] corresponding to side-effects engendered by the
     *   request.
     */
    fun createNetworkAllocation(
        request: CreateNetworkAllocationRequest
    ): Publisher<NetworkAllocationEvent>

    /**
     * Deallocate a network address to a deployed compute resource.
     *
     * @param[request]
     *   network address allocation request specification.
     *
     * @return
     *   a [Publisher] of [NetworkAllocationEvent] corresponding to side-effects engendered by the
     *   request.
     */
    fun deleteNetworkAllocation(
        request: DeleteNetworkAllocationRequest
    ): Publisher<NetworkAllocationEvent>
}

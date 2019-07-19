/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.agent.docker.api.v1_38.model

import kotlinx.serialization.Serializable

@Serializable
data class EndpointIPAMConfig(
    val IPv4Address: String? = null,
    val IPv6Address: String? = null,
    val LinkLocalIPs: List<String>? = null
)

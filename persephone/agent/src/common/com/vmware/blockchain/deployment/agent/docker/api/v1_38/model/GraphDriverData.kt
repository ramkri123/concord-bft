/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.agent.docker.api.v1_38.model

import kotlinx.serialization.Serializable

@Serializable
data class GraphDriverData(
    val Name: String,
    val Data: Map<String, String> = emptyMap()
)
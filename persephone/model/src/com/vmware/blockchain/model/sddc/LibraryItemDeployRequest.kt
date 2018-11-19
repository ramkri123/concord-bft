/* **********************************************************************
 * Copyright 2018 VMware, Inc.  All rights reserved. VMware Confidential
 * *********************************************************************/
package com.vmware.blockchain.model.sddc

data class LibraryItemDeployRequest(
    val deployment_spec: LibraryItemDeploymentSpec,
    val target: LibraryItemDeploymentTarget) {
}

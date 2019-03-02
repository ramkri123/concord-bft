/*
 * Copyright (c) 2018-2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.contracts;

/**
 * An interface for retrieving brief information about a particular contract.
 */
public interface BriefContractInfo {
    String getContractId();

    String getOwnerAddress();
}
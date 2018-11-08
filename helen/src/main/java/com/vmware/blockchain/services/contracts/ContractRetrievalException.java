/*
 * Copyright (c) 2018 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.contracts;

/**
 * <p>
 * Copyright 2018 VMware, all rights reserved.
 * </p>
 *
 * Exception thrown when there is an error during contract retrieval
 */
public class ContractRetrievalException extends Exception {
    private static final long serialVersionUID = 1L;

    public ContractRetrievalException(String message) {
        super(message);
    }
}

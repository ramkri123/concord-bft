/*
 * Copyright (c) 2018 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.ethereum;

/**
 * <p>
 * Copyright 2018 VMware, all rights reserved.
 * </p>
 * 
 * This class serves as a common exception type thrown by all EthRPC handlers.
 */
public class EthRPCHandlerException extends Exception {
    private static final long serialVersionUID = 1L;

    public EthRPCHandlerException(String message) {
        super(message);
    }
}

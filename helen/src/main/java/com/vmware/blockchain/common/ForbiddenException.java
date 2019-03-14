/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.common;

import org.springframework.http.HttpStatus;

/**
 * Forbidden access (403).
 */
public class ForbiddenException extends HelenException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message, Object... args) {
        super(HttpStatus.FORBIDDEN, message, args);
    }

}
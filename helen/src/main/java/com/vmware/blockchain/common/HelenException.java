/*
 * Copyright (c) 2018-2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.common;

import java.text.MessageFormat;

import org.springframework.http.HttpStatus;

/**
 * Base class for Helen exceptions.  The message is a MessageFormat string, to aid in transition
 * to localization latter on.
 */
public class HelenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String message;
    private final Object[] args;
    private final HttpStatus httpStatus;

    /**
     * Create a new Helen Exception.
     */
    public HelenException(HttpStatus httpStatus, String message, Object... args) {
        super(message);
        this.message = message;
        this.args = args;
        this.httpStatus = httpStatus;
    }

    public HelenException(String message, Object... args) {
        this(HttpStatus.valueOf(500), message, args);
    }

    /**
     * Create a Helen Exception, and note the original cause.
     */
    public HelenException(Throwable cause, String message, Object... args) {
        super(message, cause);
        this.message = message;
        this.args = args;
        this.httpStatus = HttpStatus.valueOf(500);
    }

    /**
     * Create a Helen Exception with a specific status, and note the original cause.
     */
    public HelenException(HttpStatus httpStatus, Throwable cause, String message, Object... args) {
        super(message, cause);
        this.message = message;
        this.args = args;
        this.httpStatus = httpStatus;
    }

    @Override
    public String getMessage() {
        return MessageFormat.format(message, args);
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

}

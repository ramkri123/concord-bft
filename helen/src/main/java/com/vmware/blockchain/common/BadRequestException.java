/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.common;

import org.springframework.http.HttpStatus;

/**
 * API has made a bad request.
 */
public class BadRequestException extends HelenException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message, Object... args) {
        super(HttpStatus.BAD_REQUEST, message, args);
    }

    public BadRequestException(Throwable cause, String message, Object... args) {
        super(HttpStatus.BAD_REQUEST, cause, message, args);
    }

    public BadRequestException(ErrorCodeType codeType, Object... args) {
        super(ExceptionMessageHelper.getMessageOrErrorCode(codeType.getErrorCodeTypeValue(),
             HttpStatus.BAD_REQUEST, args));
    }

    public BadRequestException(ErrorCodeType codeType, Throwable cause, Object... args) {
        super(ExceptionMessageHelper.getMessageOrErrorCode(codeType.getErrorCodeTypeValue(),
              HttpStatus.BAD_REQUEST, cause, args));
    }

}
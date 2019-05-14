/*
 * Copyright (c) 2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.common;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.InvalidProtocolBufferException;

import lombok.Value;

/**
 * Handle Helen exceptions, and retrun the proper error response.
 */
@RestControllerAdvice(basePackages = "com.vmware.blockchain")
public class HelenExceptionHandler {

    private static final Logger logger = LogManager.getLogger(HelenExceptionHandler.class);

    // Normally the status code is part of the Helen Exception.  There are a handful of exceptions that
    private static final Map<Class<? extends Throwable>, HttpStatus> statusCodes =
            new ImmutableMap.Builder<Class<? extends Throwable>, HttpStatus>()
                    .put(UnsupportedOperationException.class, HttpStatus.METHOD_NOT_ALLOWED)
                    .put(IllegalArgumentException.class, HttpStatus.BAD_REQUEST)
                    .put(IOException.class, HttpStatus.INTERNAL_SERVER_ERROR)
                    .put(InvalidProtocolBufferException.class, HttpStatus.INTERNAL_SERVER_ERROR)
                    .put(AccessDeniedException.class, HttpStatus.FORBIDDEN).build();


    @Value
    private static class ErrorMessage {
        String errorCode;
        String errorMessage;
        int status;
        String path;
    }

    private ErrorMessage getErrorMessage(Throwable ex, String path) {
        String errorCode = ex.getClass().getSimpleName();
        // if the exception class is in the map, use that type
        HttpStatus status = statusCodes.getOrDefault(ex.getClass(), HttpStatus.INTERNAL_SERVER_ERROR);
        // However, if this is a helen exception, pull the code from exception
        if (ex instanceof HelenException) {
            status = ((HelenException) ex).getHttpStatus();
        }
        // For now, let's always print a stacktrace.  This may change in the future.
        logger.info("Error code {}, message {}, status {}",  errorCode, ex.getMessage(), status, ex);
        return new ErrorMessage(errorCode, ex.getMessage(), status.value(), path);
    }

    /**
     * Handle all Exceptions, and return an error message.
     */
    @ExceptionHandler
    @ResponseBody
    ResponseEntity<ErrorMessage> handleException(Throwable ex, HttpServletRequest request) {
        ErrorMessage errorMessage = getErrorMessage(ex, request.getRequestURI());
        return new ResponseEntity<>(errorMessage, HttpStatus.valueOf(errorMessage.getStatus()));

    }


}
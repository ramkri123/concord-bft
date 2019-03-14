/* **************************************************************************
 * Copyright (c) 2019 VMware, Inc.  All rights reserved. VMware Confidential
 * *************************************************************************/
package com.vmware.blockchain.deployment.http

import com.vmware.blockchain.deployment.model.core.Credential
import com.vmware.blockchain.deployment.model.core.URI

/**
 * An abstract contract implementation of an HTTP client that is access-token aware.
 */
expect abstract class AccessTokenAwareHttpClient {

    /**
     * Retrieve the API session token from a given session response.
     *
     * @return
     *   API session token as a [String].
     */
    protected abstract fun retrieveAccessToken(sessionResponse: HttpResponse<String>): String

    /**
     * Retrieve the HTTP header name corresponding to the access token.
     *
     * @return
     *   the HTTP header name for access token.
     */
    protected abstract fun accessTokenHeader(): String

    /**
     * Obtain the session creation URI associated with this client instance.
     *
     * @return
     *   the session-creation [URI] value.
     */
    protected abstract fun session(): URI

    /**
     * Obtain the authentication credential associated with this client instance.
     *
     * @return
     *   the [Credential] instance.
     */
    protected abstract fun credential(): Credential

    /**
     * Send a HTTP GET with content specified by parameter and return the response with response
     * body mapped to a typed instance if request was successful.
     *
     * @param[path]
     *   path to be resolved against the base service endpoint.
     * @param[contentType]
     *   HTTP content type.
     * @param[headers]
     *   list of HTTP headers to be set for the request.
     *
     * @return
     *   the response of the request as a parameterized (data-bound) [HttpResponse] instance.
     */
    suspend inline fun <reified T> get(
        path: String,
        contentType: String,
        headers: List<Pair<String, String>>
    ): HttpResponse<T?>

    /**
     * Send a HTTP PATCH with content specified by parameter and return the response with response
     * body mapped to a typed instance if request was successful.
     *
     * @param[path]
     *   path to be resolved against the base service endpoint.
     * @param[contentType]
     *   HTTP content type.
     * @param[headers]
     *   list of HTTP headers to be set for the request.
     * @param[body]
     *   request body.
     *
     * @return
     *   the response of the request as a parameterized (data-bound) [HttpResponse] instance.
     */
    suspend inline fun <reified R, reified T> patch(
        path: String,
        contentType: String,
        headers: List<Pair<String, String>>,
        body: R?
    ): HttpResponse<T?>

    /**
     * Send a HTTP POST with content specified by parameter and return the response with response
     * body mapped to a typed instance if request was successful.
     *
     * @param[path]
     *   path to be resolved against the base service endpoint.
     * @param[contentType]
     *   HTTP content type.
     * @param[headers]
     *   list of HTTP headers to be set for the request.
     * @param[body]
     *   request body.
     *
     * @return
     *   the response of the request as a parameterized (data-bound) [HttpResponse] instance.
     */
    suspend inline fun <reified R, reified T> post(
        path: String,
        contentType: String,
        headers: List<Pair<String, String>>,
        body: R?
    ): HttpResponse<T?>

    /**
     * Send a HTTP DELETE with content specified by parameter and return the response with response
     * body mapped to a typed instance if request was successful.
     *
     * @param[path]
     *   path to be resolved against the base service endpoint.
     * @param[contentType]
     *   HTTP content type.
     * @param[headers]
     *   list of HTTP headers to be set for the request.
     * @return
     *   the response of the request as a parameterized (data-bound) [HttpResponse] instance.
     */
    suspend inline fun <reified T> delete(
        path: String,
        contentType: String,
        headers: List<Pair<String, String>>
    ): HttpResponse<T?>
}
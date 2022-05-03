//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.api;

import java.net.HttpCookie;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * The HTTP Upgrade to WebSocket Request
 */
public interface UpgradeRequest
{
    /**
     * Get the list of Cookies on the Upgrade request
     *
     * @return the list of Cookies
     */
    List<HttpCookie> getCookies();

    /**
     * Get the list of WebSocket Extension Configurations for this Upgrade Request.
     * <p>
     * This is merely the list of requested Extensions to use, see {@link UpgradeResponse#getExtensions()} for what was
     * negotiated
     *
     * @return the list of Extension configurations (in the order they were specified)
     */
    List<ExtensionConfig> getExtensions();

    /**
     * Get a specific Header value from Upgrade Request
     *
     * @param name the name of the header
     * @return the value of the header (null if header does not exist)
     */
    String getHeader(String name);

    /**
     * Get the specific Header value, as an {@code int}, from the Upgrade Request.
     *
     * @param name the name of the header
     * @return the value of the header as an {@code int} (-1 if header does not exist)
     * @throws NumberFormatException if unable to parse value as an int.
     */
    int getHeaderInt(String name);

    /**
     * Get the headers as a Map of keys to value lists.
     *
     * @return the headers
     */
    Map<String, List<String>> getHeaders();

    /**
     * Get the specific header values (for multi-value headers)
     *
     * @param name the header name
     * @return the value list (null if no header exists)
     */
    List<String> getHeaders(String name);

    /**
     * The host of the Upgrade Request URI
     *
     * @return host of the request URI
     */
    String getHost();

    /**
     * The HTTP version used for this Upgrade Request
     * <p>
     * As of <a href="http://tools.ietf.org/html/rfc6455">RFC6455 (December 2011)</a> this is always
     * {@code HTTP/1.1}
     *
     * @return the HTTP Version used
     */
    String getHttpVersion();

    /**
     * The HTTP method for this Upgrade Request.
     * <p>
     * As of <a href="http://tools.ietf.org/html/rfc6455">RFC6455 (December 2011)</a> this is always {@code GET}
     *
     * @return the HTTP method used
     */
    String getMethod();

    /**
     * The WebSocket Origin of this Upgrade Request
     * <p>
     * See <a href="http://tools.ietf.org/html/rfc6455#section-10.2">RFC6455: Section 10.2</a> for details.
     * <p>
     * Equivalent to {@link #getHeader(String)} passed the "Origin" header.
     *
     * @return the Origin header
     */
    String getOrigin();

    /**
     * Returns a map of the query parameters of the request.
     *
     * @return a unmodifiable map of query parameters of the request.
     */
    Map<String, List<String>> getParameterMap();

    /**
     * Get the WebSocket Protocol Version
     * <p>
     * As of <a href="http://tools.ietf.org/html/rfc6455#section-11.6">RFC6455</a>, Jetty only supports version
     * {@code 13}
     *
     * @return the WebSocket protocol version
     */
    String getProtocolVersion();

    /**
     * Get the Query String of the request URI.
     *
     * @return the request uri query string
     */
    String getQueryString();

    /**
     * Get the Request URI
     *
     * @return the request URI
     */
    URI getRequestURI();

    /**
     * Get the list of offered WebSocket sub-protocols.
     *
     * @return the list of offered sub-protocols
     */
    List<String> getSubProtocols();

    /**
     * Get the User Principal for this request.
     * <p>
     * Only applicable when using UpgradeRequest from server side.
     *
     * @return the user principal
     */
    Principal getUserPrincipal();

    /**
     * Test if a specific sub-protocol is offered
     *
     * @param test the sub-protocol to test for
     * @return true if sub-protocol exists on request
     */
    boolean hasSubProtocol(String test);

    /**
     * Test if connection is secure.
     *
     * @return true if connection is secure.
     */
    boolean isSecure();
}

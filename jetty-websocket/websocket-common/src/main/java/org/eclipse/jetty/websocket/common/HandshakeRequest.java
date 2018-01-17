//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common;

import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * An immutable interface to the Upgrade Request.
 */
public interface HandshakeRequest
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
     * This is merely the list of requested Extensions to use, see {@link HandshakeResponse#getExtensions()} for what was
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
    Map<String, List<String>> getHeadersMap();

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
     * Eg: if upgrade used <a href="http://tools.ietf.org/html/rfc6455">RFC6455 (December 2011)</a> then this results in {@code HTTP/1.1}
     *
     * @return the HTTP Version used
     */
    // TODO: this should be expanded from just HTTP/1.1 and HTTP/2 to also UnixSocket. perhaps a new name?
    String getHttpVersion();

    /**
     * The Local Socket Address for the Request.
     * <p>
     *     Do not assume that this will return a {@link InetSocketAddress} in all cases.
     *     Use of various proxies, and even UnixSockets can result a SocketAddress being returned
     *     without supporting {@link InetSocketAddress}
     * </p>
     *
     * @return the SocketAddress for the local connection
     */
    SocketAddress getLocalSocketAddress();

    /**
     * The Remote Socket Address for the Request.
     * <p>
     *     Do not assume that this will return a {@link InetSocketAddress} in all cases.
     *     Use of various proxies, and even UnixSockets can result a SocketAddress being returned
     *     without supporting {@link InetSocketAddress}
     * </p>
     *
     * @return the SocketAddress for the remote connection
     */
    SocketAddress getRemoteSocketAddress();

    /**
     * The Client Locale preference
     *
     * @return the preferred <code>Locale</code> for the client
     */
    Locale getLocale();

    /**
     * The list of preferred Client Locales in order of preference.
     *
     * @return an Enumeration of preferred Locale objects
     */
    Enumeration<Locale> getLocales();

    /**
     * The HTTP method for this Upgrade Request.
     * <p>
     * As of <a href="http://tools.ietf.org/html/rfc6455">RFC6455 (December 2011)</a> this is always {@code GET}
     *
     * @return the HTTP method used
     */
    String getMethod();

    /**
     * The HTTP and WebSocket Origin Header.
     *
     * @see <a href="https://tools.ietf.org/html/rfc6455#section-10.2">RFC6455 - Origin Considerations</a>
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
     * Test if a specific sub-protocol is offered
     *
     * @param test the sub-protocol to test for
     * @return true if sub-protocol exists on request
     */
    boolean hasSubProtocol(String test);

    /**
     * The Request determined that the connection was secure.
     *
     * <p>
     *     Eg: if this is a HttpServletRequest, then the HttpServletRequest.isSecure() is used,
     *     but in the case of a Client request, then the outgoing scheme is used.
     * </p>
     *
     * @return true if connection is secure.
     */
    boolean isSecure();
}

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

package org.eclipse.jetty.websocket.api;

import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * The mutable HTTP Request to Upgrade to WebSocket
 */
@SuppressWarnings("unused")
public interface UpgradeRequest extends HandshakeRequest
{
    /**
     * Add WebSocket Extension Configuration(s) to Upgrade Request.
     * <p>
     * This is merely the list of requested Extensions to use, see {@link UpgradeResponse#getExtensions()} for what was
     * negotiated
     *
     * @param configs the configuration(s) to add
     */
    void addExtensions(ExtensionConfig... configs);

    /**
     * Add WebSocket Extension Configuration(s) to request
     * <p>
     * This is merely the list of requested Extensions to use, see {@link UpgradeResponse#getExtensions()} for what was
     * negotiated
     *
     * @param configs the configuration(s) to add
     */
    void addExtensions(String... configs);

    /**
     * Set the list of Cookies on the request
     *
     * @param cookies the cookies to use
     */
    void setCookies(List<HttpCookie> cookies);

    /**
     * Set the list of WebSocket Extension configurations on the request.
     * @param configs the list of extension configurations
     */
    void setExtensions(List<ExtensionConfig> configs);

    /**
     * Set a specific header with multi-value field
     * <p>
     * Overrides any previous value for this named header
     *
     * @param name the name of the header
     * @param values the multi-value field
     */
    void setHeader(String name, List<String> values);

    /**
     * Set a specific header value
     * <p>
     * Overrides any previous value for this named header
     *
     * @param name the header to set
     * @param value the value to set it to
     */
    void setHeader(String name, String value);

    /**
     * Sets multiple headers on the request.
     * <p>
     * Only sets those headers provided, does not remove
     * headers that exist on request and are not provided in the
     * parameter for this method.
     * <p>
     * Convenience method vs calling {@link #setHeader(String, List)} multiple times.
     *
     * @param headers the headers to set
     */
    void setHeaders(Map<String, List<String>> headers);

    /**
     * Set the HTTP Version to use.
     * <p>
     * As of <a href="http://tools.ietf.org/html/rfc6455">RFC6455 (December 2011)</a> this should always be
     * {@code HTTP/1.1}
     *
     * @param httpVersion the HTTP version to use.
     */
    void setHttpVersion(String httpVersion);

    /**
     * Set the HTTP method to use.
     * <p>
     * As of <a href="http://tools.ietf.org/html/rfc6455">RFC6455 (December 2011)</a> this is always {@code GET}
     *
     * @param method the HTTP method to use.
     */
    void setMethod(String method);

    /**
     * Set the Request URI to use for this request.
     * <p>
     * Must be an absolute URI with scheme {@code 'ws'} or {@code 'wss'}
     *
     * @param uri the Request URI
     */
    void setRequestURI(URI uri);

    /**
     * Set the offered WebSocket Sub-Protocol list.
     *
     * @param protocols the offered sub-protocol list
     */
    void setSubProtocols(List<String> protocols);

    /**
     * Set the offered WebSocket Sub-Protocol list.
     *
     * @param protocols the offered sub-protocol list
     */
    void setSubProtocols(String... protocols);
}

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

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * The mutable HTTP Response to Upgrade to WebSocket
 */
public interface UpgradeResponse extends HandshakeResponse
{
    /**
     * Add a header value to the response.
     * <p>
     *  If header already exists, a new value is appended to the end of value list per HTTP rules
     * </p>
     *
     * @param name the header name
     * @param value the header value
     */
    void addHeader(String name, String value);

    /**
     * Respond with HTTP error (and optionally process the default Servlet error handling)
     * <p>
     * This means that the websocket endpoint will not upgrade
     *
     * @param statusCode the HTTP response status code
     * @throws IOException
     * if unable to send the error
     */
    void sendError(int statusCode) throws IOException;

    /**
     * Issue a forbidden upgrade response.
     * <p>
     * This means that the websocket endpoint was valid, but the conditions to use a WebSocket resulted in a forbidden
     * access.
     * <p>
     * Use this when the origin or authentication is invalid.
     *
     * @param message
     * the short 1 line detail message about the forbidden response
     * @throws IOException
     * if unable to send the forbidden
     */
    void sendForbidden(String message) throws IOException;

    /**
     * Set the accepted WebSocket Protocol.
     *
     * @param protocol
     * the protocol to list as accepted
     */
    void setAcceptedSubProtocol(String protocol);

    /**
     * Set the list of extensions that are approved for use with this websocket.
     * <p>
     * Notes:
     * <ul>
     * <li>Per the spec you cannot add extensions that have not been seen in the {@link UpgradeRequest}, just remove
     * entries you don't want to use</li>
     * <li>If this is unused, or a null is passed, then the list negotiation will follow default behavior and use the
     * complete list of extensions that are
     * available in this WebSocket server implementation.</li>
     * </ul>
     *
     * @param extensions
     * the list of extensions to use.
     */
    void setExtensions(List<ExtensionConfig> extensions);

    /**
     * Set a header
     * <p>
     * Overrides previous value of header (if set)
     *
     * @param name the header name
     * @param value the header value
     */
    void setHeader(String name, String value);
}

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

package org.eclipse.jetty.ee10.websocket.server;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.ee10.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee10.websocket.api.UpgradeRequest;
import org.eclipse.jetty.ee10.websocket.api.UpgradeResponse;

public interface JettyServerUpgradeResponse extends UpgradeResponse
{
    /**
     * Add a header value to the response.
     *
     * @param name the header name
     * @param value the header value
     */
    void addHeader(String name, String value);

    /**
     * Set a header
     * <p>
     * Overrides previous value of header (if set)
     *
     * @param name the header name
     * @param value the header value
     */
    void setHeader(String name, String value);

    /**
     * Set a header
     * <p>
     * Overrides previous value of header (if set)
     *
     * @param name the header name
     * @param values the header values
     */
    void setHeader(String name, List<String> values);

    /**
     * Issue a forbidden upgrade response.
     * <p>
     * This means that the websocket endpoint was valid, but the conditions to use a WebSocket resulted in a forbidden
     * access.
     * <p>
     * Use this when the origin or authentication is invalid.
     *
     * @param message the short 1 line detail message about the forbidden response
     * @throws IOException if unable to send the forbidden
     */
    void sendForbidden(String message) throws IOException;

    /**
     * Sends an error response to the client using the specified status.
     * @param statusCode the error status code
     * @param message the descriptive message
     * @throws IOException If an input or output exception occurs
     * @throws IllegalStateException If the response was committed
     */
    void sendError(int statusCode, String message) throws IOException;

    /**
     * Set the accepted WebSocket Protocol.
     *
     * @param protocol the protocol to list as accepted
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
     * @param extensions the list of extensions to use.
     */
    void setExtensions(List<ExtensionConfig> extensions);

    /**
     * Set the HTTP Response status code
     *
     * @param statusCode the status code
     */
    void setStatusCode(int statusCode);

    /**
     * Returns a boolean indicating if the response has been committed.
     * A committed response has already had its status code and headers written.
     * @return a boolean indicating if the response has been committed.
     */
    boolean isCommitted();
}

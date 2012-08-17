//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public interface UpgradeResponse
{
    public void addHeader(String name, String value);

    /**
     * Get the accepted WebSocket protocol.
     * 
     * @return the accepted WebSocket protocol.
     */
    public String getAcceptedSubProtocol();

    /**
     * Get the list of extensions that should be used for the websocket.
     * 
     * @return the list of negotiated extensions to use.
     */
    public List<ExtensionConfig> getExtensions();

    public Set<String> getHeaderNamesSet();

    public String getHeaderValue(String name);

    public Iterator<String> getHeaderValues(String name);

    public int getStatusCode();

    public String getStatusReason();

    public boolean isSuccess();

    /**
     * Issue a forbidden upgrade response.
     * <p>
     * This means that the websocket endpoint was valid, but the conditions to use a WebSocket resulted in a forbidden access.
     * <p>
     * Use this when the origin or authentication is invalid.
     * 
     * @param message
     *            the short 1 line detail message about the forbidden response
     * @throws IOException
     */
    public void sendForbidden(String message) throws IOException;

    /**
     * Set the accepted WebSocket Protocol.
     * 
     * @param protocol
     *            the protocol to list as accepted
     */
    public void setAcceptedSubProtocol(String protocol);

    /**
     * Set the list of extensions that are approved for use with this websocket.
     * <p>
     * This is Advanced usage of the {@link WebSocketCreator} to allow for a custom set of negotiated extensions.
     * <p>
     * Notes:
     * <ul>
     * <li>Per the spec you cannot add extensions that have not been seen in the {@link UpgradeRequest}, just remove entries you don't want to use</li>
     * <li>If this is unused, or a null is passed, then the list negotiation will follow default behavior and use the complete list of extensions that are
     * available in this WebSocket server implementation.</li>
     * </ul>
     * 
     * @param extensions
     *            the list of extensions to use.
     */
    public void setExtensions(List<ExtensionConfig> extensions);

    public void setHeader(String name, String value);

    public void validateWebSocketHash(String expectedHash) throws UpgradeException;
}

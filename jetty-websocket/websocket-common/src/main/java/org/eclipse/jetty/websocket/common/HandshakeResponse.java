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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

/**
 * An immutable interface to the HTTP Upgrade to WebSocket Response
 */
public interface HandshakeResponse
{
    /**
     * Get the accepted WebSocket protocol.
     *
     * @return the accepted WebSocket protocol.
     */
    String getAcceptedSubProtocol();

    /**
     * Get the list of extensions that should be used for the websocket.
     *
     * @return the list of negotiated extensions to use.
     */
    List<ExtensionConfig> getExtensions();

    /**
     * Get a header value
     *
     * @param name the header name
     * @return the value (null if header doesn't exist)
     */
    String getHeader(String name);

    /**
     * Get the header names
     *
     * @return the set of header names
     */
    Set<String> getHeaderNames();

    /**
     * Get the headers map
     *
     * @return the map of headers
     */
    Map<String, List<String>> getHeadersMap();

    /**
     * Get the multi-value header value
     *
     * @param name the header name
     * @return the list of values (null if header doesn't exist)
     */
    List<String> getHeaders(String name);

    /**
     * Get the HTTP Response Status Code
     *
     * @return the status code
     */
    int getStatusCode();
}

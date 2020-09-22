//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HTTP Upgrade to WebSocket Response
 */
public interface UpgradeResponse
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
    Map<String, List<String>> getHeaders();

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

//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.Objects;
import javax.servlet.http.HttpServletRequest;

/**
 * Customizes requests that lack the {@code Host} header (for example, HTTP 1.0 requests).
 * <p>
 * In case of HTTP 1.0 requests that lack the {@code Host} header, the application may issue
 * a redirect, and the {@code Location} header is usually constructed from the {@code Host}
 * header; if the {@code Host} header is missing, the server may query the connector for its
 * IP address in order to construct the {@code Location} header, and thus leak to clients
 * internal IP addresses.
 * <p>
 * This {@link HttpConfiguration.Customizer} is configured with a {@code serverName} and
 * optionally a {@code serverPort}.
 * If the {@code Host} header is absent, the configured {@code serverName} will be set on
 * the request so that {@link HttpServletRequest#getServerName()} will return that value,
 * and likewise for {@code serverPort} and {@link HttpServletRequest#getServerPort()}.
 */
public class HostHeaderCustomizer implements HttpConfiguration.Customizer
{
    private final String serverName;
    private final int serverPort;

    /**
     * @param serverName the {@code serverName} to set on the request (the {@code serverPort} will not be set)
     */
    public HostHeaderCustomizer(String serverName)
    {
        this(serverName, 0);
    }

    /**
     * @param serverName the {@code serverName} to set on the request
     * @param serverPort the {@code serverPort} to set on the request
     */
    public HostHeaderCustomizer(String serverName, int serverPort)
    {
        this.serverName = Objects.requireNonNull(serverName);
        this.serverPort = serverPort;
    }

    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        if (request.getHeader("Host") == null)
            request.setAuthority(serverName, serverPort);  // TODO set the field as well?
    }
}

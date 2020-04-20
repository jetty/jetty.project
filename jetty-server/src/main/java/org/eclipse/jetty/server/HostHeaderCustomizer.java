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

package org.eclipse.jetty.server;

import java.util.Objects;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpURI;

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
            // TODO set the field as well?
            request.setHttpURI(HttpURI.build(request.getHttpURI()).host(serverName).port(serverPort));
    }
}

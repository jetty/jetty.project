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

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;

/**
 * Adds a missing {@code Host} header (for example, HTTP 1.0 or 2.0 requests).
 * <p>
 * The host and port may be provided in the constructor or taken from the
 * {@link Request#getServerName()} and {@link Request#getServerPort()} methods.
 * </p>
 */
public class HostHeaderCustomizer implements HttpConfiguration.Customizer
{
    private final String serverName;
    private final int serverPort;

    public HostHeaderCustomizer()
    {
        this(null, 0);
    }

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
        if (request.getHttpVersion() != HttpVersion.HTTP_1_1 && !request.getHttpFields().contains(HttpHeader.HOST))
        {
            String host = serverName == null ? request.getServerName() : serverName;
            int port = serverPort == 0 ? request.getServerPort() : 0;
            String proto = request.getProtocol();

            if ((HttpScheme.HTTPS.is(proto) && port == 443) || (HttpScheme.HTTP.is(proto) && port == 80))
                port = 0;

            HttpFields original = request.getHttpFields();
            HttpFields.Mutable httpFields = HttpFields.build(original.size() + 1);
            httpFields.add(new HostPortHttpField(host, port));
            httpFields.add(request.getHttpFields());
            request.setHttpFields(httpFields);
        }
    }
}

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

package org.eclipse.jetty.server;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
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

    /**
     * Construct customizer that uses {@link Request#getServerName()} and
     * {@link Request#getServerPort()} to create a host header.
     */
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
        this.serverName = serverName;
        this.serverPort = serverPort;
    }

    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        if (request.getHttpVersion() != HttpVersion.HTTP_1_1 && !request.getHttpFields().contains(HttpHeader.HOST))
        {
            String host = serverName == null ? request.getServerName() : serverName;
            int port = HttpScheme.normalizePort(request.getScheme(), serverPort == 0 ? request.getServerPort() : serverPort);

            if (serverName != null || serverPort > 0)
                request.setHttpURI(HttpURI.build(request.getHttpURI()).authority(host, port));

            HttpFields original = request.getHttpFields();
            HttpFields.Mutable httpFields = HttpFields.build(original.size() + 1);
            httpFields.add(new HostPortHttpField(host, port));
            httpFields.add(request.getHttpFields());
            request.setHttpFields(httpFields);
        }
    }
}

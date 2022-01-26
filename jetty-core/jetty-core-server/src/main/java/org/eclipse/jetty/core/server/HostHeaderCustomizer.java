//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
        this.serverName = serverName;
        this.serverPort = serverPort;
    }

    @Override
    public Request customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        if (request.getConnectionMetaData().getVersion() == HttpVersion.HTTP_1_1 || request.getHeaders().contains(HttpHeader.HOST))
            return request;

        String host = serverName == null ? request.getServerName() : serverName;
        int port = HttpScheme.normalizePort(request.getHttpURI().getScheme(), serverPort == 0 ? request.getServerPort() : serverPort);

        HttpURI uri = (serverName != null || serverPort > 0)
            ? HttpURI.build(request.getHttpURI()).authority(host, port).asImmutable()
            : request.getHttpURI();

        HttpFields original = request.getHeaders();
        HttpFields.Mutable builder = HttpFields.build(original.size() + 1);
        builder.add(new HostPortHttpField(host, port));
        builder.add(request.getHeaders());
        HttpFields headers = builder.asImmutable();

        return new Request.Wrapper(request)
        {
            @Override
            public HttpURI getHttpURI()
            {
                return uri;
            }

            @Override
            public HttpFields getHeaders()
            {
                return headers;
            }
        };
    }
}
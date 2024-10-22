//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>A {@link HttpConfiguration.Customizer} that synthesizes the authority when the
 * {@link HttpHeader#C_AUTHORITY} header is missing.</p>
 * <p>After customization, the synthesized authority is accessible via
 * {@link HttpURI#getAuthority()} from the {@link Request} object.</p>
 * <p>The authority is synthesized from the {@code Host} header.
 * If the {@code Host} header is also missing, it is synthesized using
 * {@link Request#getServerName(Request)} and {@link Request#getServerPort(Request)}.</p>
 */
public class AuthorityCustomizer implements HttpConfiguration.Customizer
{
    @Override
    public Request customize(Request request, HttpFields.Mutable responseHeaders)
    {
        if (request.getConnectionMetaData().getHttpVersion().getVersion() < 20)
            return request;

        HttpURI httpURI = request.getHttpURI();
        if (httpURI.hasAuthority() && !httpURI.getAuthority().isEmpty())
            return request;

        String hostPort = request.getHeaders().get(HttpHeader.HOST);
        if (hostPort == null)
        {
            String host = Request.getServerName(request);
            int port = URIUtil.normalizePortForScheme(httpURI.getScheme(), Request.getServerPort(request));
            hostPort = new HostPort(host, port).toString();
        }

        HttpURI newHttpURI = HttpURI.build(httpURI).authority(hostPort).asImmutable();
        return new Request.Wrapper(request)
        {
            @Override
            public HttpURI getHttpURI()
            {
                return newHttpURI;
            }
        };
    }
}

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

package org.eclipse.jetty.client;

import java.net.URI;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

/**
 * <p>A protocol handler that handles the 401 response code
 * in association with the {@code Proxy-Authenticate} header.</p>
 *
 * @see WWWAuthenticationProtocolHandler
 */
public class ProxyAuthenticationProtocolHandler extends AuthenticationProtocolHandler
{
    public static final String NAME = "proxy-authenticate";
    private static final String ATTRIBUTE = ProxyAuthenticationProtocolHandler.class.getName() + ".attribute";

    public ProxyAuthenticationProtocolHandler(HttpClient client)
    {
        this(client, DEFAULT_MAX_CONTENT_LENGTH);
    }

    public ProxyAuthenticationProtocolHandler(HttpClient client, int maxContentLength)
    {
        super(client, maxContentLength);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return response.getStatus() == HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407;
    }

    @Override
    protected HttpHeader getAuthenticateHeader()
    {
        return HttpHeader.PROXY_AUTHENTICATE;
    }

    @Override
    protected HttpHeader getAuthorizationHeader()
    {
        return HttpHeader.PROXY_AUTHORIZATION;
    }

    @Override
    protected URI getAuthenticationURI(Request request)
    {
        HttpDestination destination = (HttpDestination)getHttpClient().resolveDestination(request);
        ProxyConfiguration.Proxy proxy = destination.getProxy();
        return proxy != null ? proxy.getURI() : request.getURI();
    }

    @Override
    protected String getAuthenticationAttribute()
    {
        return ATTRIBUTE;
    }
}

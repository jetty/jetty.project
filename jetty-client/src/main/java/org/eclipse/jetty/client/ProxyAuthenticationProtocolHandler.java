//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
        HttpDestination destination = getHttpClient().destinationFor(request.getScheme(), request.getHost(), request.getPort());
        ProxyConfiguration.Proxy proxy = destination.getProxy();
        return proxy != null ? proxy.getURI() : request.getURI();
    }

    @Override
    protected String getAuthenticationAttribute()
    {
        return ATTRIBUTE;
    }
}

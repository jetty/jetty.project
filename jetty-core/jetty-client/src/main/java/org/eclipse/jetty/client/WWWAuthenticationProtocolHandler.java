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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

/**
 * <p>A protocol handler that handles the 401 response code
 * in association with the {@code WWW-Authenticate} header.</p>
 *
 * @see ProxyAuthenticationProtocolHandler
 */
public class WWWAuthenticationProtocolHandler extends AuthenticationProtocolHandler
{
    public static final String NAME = "www-authenticate";
    private static final String ATTRIBUTE = WWWAuthenticationProtocolHandler.class.getName() + ".attribute";

    public WWWAuthenticationProtocolHandler(HttpClient client)
    {
        this(client, DEFAULT_MAX_CONTENT_LENGTH);
    }

    public WWWAuthenticationProtocolHandler(HttpClient client, int maxContentLength)
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
        return response.getStatus() == HttpStatus.UNAUTHORIZED_401;
    }

    @Override
    protected HttpHeader getAuthenticateHeader()
    {
        return HttpHeader.WWW_AUTHENTICATE;
    }

    @Override
    protected HttpHeader getAuthorizationHeader()
    {
        return HttpHeader.AUTHORIZATION;
    }

    @Override
    protected URI getAuthenticationURI(Request request)
    {
        return request.getURI();
    }

    @Override
    protected String getAuthenticationAttribute()
    {
        return ATTRIBUTE;
    }
}

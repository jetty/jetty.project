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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.URIUtil;

/**
 * Forces a redirect to the secure form of the resource before allowed to access the resource.
 *
 * <p>
 * {@link SecuredRedirectHandler} enforces that all requests passing through it must have {@link Request#isSecure()} set to true.
 * </p>
 * <p>
 * {@link SecuredRedirectHandler} will send a Redirect HTTP Response (default to {@code 302: Moved Temporarily})
 * with the information present in the {@link HttpConfiguration} for the connector that the response is being processed on.
 * The {@code Location} response header will be built from {@link HttpConfiguration#getSecureScheme()} and
 * {@link HttpConfiguration#getSecurePort()}
 * </p>
 */
public class SecuredRedirectHandler extends Handler.Wrapper
{
    /**
     * The redirect code to send in response.
     */
    private final int _redirectCode;

    /**
     * Uses moved temporarily code (302) as the redirect code.
     */
    public SecuredRedirectHandler()
    {
        this(HttpStatus.MOVED_TEMPORARILY_302);
    }

    /**
     * Use supplied code as the redirect code.
     *
     * @param code the redirect code to use in the response
     * @throws IllegalArgumentException if parameter is an invalid redirect code
     */
    public SecuredRedirectHandler(final int code)
    {
        if (!HttpStatus.isRedirection(code))
            throw new IllegalArgumentException("Not a 3xx redirect code");
        _redirectCode = code;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (request.isSecure())
        {
            // Nothing to do here.
            return super.handle(request, response, callback);
        }

        HttpConfiguration httpConfig = request.getConnectionMetaData().getHttpConfiguration();

        int securePort = httpConfig.getSecurePort();
        if (securePort > 0)
        {
            String secureScheme = httpConfig.getSecureScheme();
            String url = URIUtil.newURI(secureScheme, Request.getServerName(request), securePort, request.getHttpURI().getPath(), request.getHttpURI().getQuery());
            // TODO need a utility for this
            response.getHeaders().put(HttpHeader.LOCATION, url);
            response.setStatus(_redirectCode);
            response.write(true, null, callback);
        }
        else
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "HttpConfiguration.securePort not configured");
        }
        return true;
    }
}

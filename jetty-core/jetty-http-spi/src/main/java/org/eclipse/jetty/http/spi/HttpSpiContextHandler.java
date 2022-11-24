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

package org.eclipse.jetty.http.spi;

import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Authenticator.Result;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty handler that bridges requests to {@link HttpHandler}.
 */
public class HttpSpiContextHandler extends ContextHandler
{
    public static final Logger LOG = LoggerFactory.getLogger(HttpSpiContextHandler.class);

    private final HttpContext _httpContext;

    private HttpHandler _httpHandler;

    public HttpSpiContextHandler(HttpContext httpContext, HttpHandler httpHandler)
    {
        this._httpContext = httpContext;
        this._httpHandler = httpHandler;
        super.setHandler(new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback)
            {
                try (HttpExchange jettyHttpExchange = request.isSecure()
                    ? new JettyHttpsExchange(_httpContext, request, response)
                    : new JettyHttpExchange(_httpContext, request, response))
                {
                    Authenticator auth = _httpContext.getAuthenticator();
                    if (auth != null && handleAuthentication(request, response, callback, jettyHttpExchange, auth))
                        return;

                    _httpHandler.handle(jettyHttpExchange);
                    callback.succeeded();
                }
                catch (Exception ex)
                {
                    LOG.debug("Failed to handle", ex);
                    Response.writeError(request, response, callback, 500, null, ex);
                }
            }
        });
    }

    @Override
    public void setHandler(Handler handler)
    {
        throw new UnsupportedOperationException();
    }

    private boolean handleAuthentication(
        Request request,
        Response response,
        Callback callback,
        HttpExchange httpExchange,
        Authenticator auth)
    {
        Result result = auth.authenticate(httpExchange);
        if (result instanceof Authenticator.Failure)
        {
            int rc = ((Authenticator.Failure)result).getResponseCode();
            for (Map.Entry<String, List<String>> header : httpExchange.getResponseHeaders().entrySet())
            {
                for (String value : header.getValue())
                    response.getHeaders().add(header.getKey(), value);
            }
            Response.writeError(request, response, callback, rc);
            return true;
        }

        if (result instanceof Authenticator.Retry)
        {
            int rc = ((Authenticator.Retry)result).getResponseCode();
            for (Map.Entry<String, List<String>> header : httpExchange.getResponseHeaders().entrySet())
            {
                for (String value : header.getValue())
                {
                    response.getHeaders().add(header.getKey(), value);
                }
            }
            Response.writeError(request, response, callback, rc);
            return true;
        }

        if (result instanceof Authenticator.Success)
        {
            HttpPrincipal principal = ((Authenticator.Success)result).getPrincipal();
            ((JettyExchange)httpExchange).setPrincipal(principal);
            return false;
        }

        Response.writeError(request, response, callback, 500);
        return true;
    }

    public HttpHandler getHttpHandler()
    {
        return _httpHandler;
    }

    public void setHttpHandler(HttpHandler handler)
    {
        this._httpHandler = handler;
    }
}

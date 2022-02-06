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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Authenticator.Result;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty handler that bridges requests to {@link HttpHandler}.
 */
public class HttpSpiContextHandler extends ContextHandler
{
    public static final Logger LOG = LoggerFactory.getLogger(HttpSpiContextHandler.class);

    private HttpContext _httpContext;

    private HttpHandler _httpHandler;

    public HttpSpiContextHandler(HttpContext httpContext, HttpHandler httpHandler)
    {
        this._httpContext = httpContext;
        this._httpHandler = httpHandler;
    }

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
    {
        if (!target.startsWith(getContextPath()))
        {
            return;
        }

        HttpExchange jettyHttpExchange;
        if (baseRequest.isSecure())
        {
            jettyHttpExchange = new JettyHttpsExchange(_httpContext, req, resp);
        }
        else
        {
            jettyHttpExchange = new JettyHttpExchange(_httpContext, req, resp);
        }

        // TODO: add filters processing

        try
        {
            Authenticator auth = _httpContext.getAuthenticator();
            if (auth != null)
            {
                handleAuthentication(resp, jettyHttpExchange, auth);
            }
            else
            {
                _httpHandler.handle(jettyHttpExchange);
            }
        }
        catch (Exception ex)
        {
            LOG.debug("Failed to handle", ex);
            PrintWriter writer = new PrintWriter(jettyHttpExchange.getResponseBody());

            resp.setStatus(500);
            writer.println("<h2>HTTP ERROR: 500</h2>");
            writer.println("<pre>INTERNAL_SERVER_ERROR</pre>");
            writer.println("<p>RequestURI=" + StringUtil.sanitizeXmlString(req.getRequestURI()) + "</p>");

            if (LOG.isDebugEnabled())
            {
                writer.println("<pre>");
                ex.printStackTrace(writer);
                writer.println("</pre>");
            }

            baseRequest.getHttpChannel().getHttpConfiguration().writePoweredBy(writer, "<p>", "</p>");

            writer.close();
        }
        finally
        {
            baseRequest.setHandled(true);
        }
    }

    private void handleAuthentication(HttpServletResponse resp, HttpExchange httpExchange, Authenticator auth) throws IOException
    {
        Result result = auth.authenticate(httpExchange);
        if (result instanceof Authenticator.Failure)
        {
            int rc = ((Authenticator.Failure)result).getResponseCode();
            for (Map.Entry<String, List<String>> header : httpExchange.getResponseHeaders().entrySet())
            {
                for (String value : header.getValue())
                {
                    resp.addHeader(header.getKey(), value);
                }
            }
            resp.sendError(rc);
        }
        else if (result instanceof Authenticator.Retry)
        {
            int rc = ((Authenticator.Retry)result).getResponseCode();
            for (Map.Entry<String, List<String>> header : httpExchange.getResponseHeaders().entrySet())
            {
                for (String value : header.getValue())
                {
                    resp.addHeader(header.getKey(), value);
                }
            }
            resp.setStatus(rc);
            resp.flushBuffer();
        }
        else if (result instanceof Authenticator.Success)
        {
            HttpPrincipal principal = ((Authenticator.Success)result).getPrincipal();
            ((JettyExchange)httpExchange).setPrincipal(principal);
            _httpHandler.handle(httpExchange);
        }
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

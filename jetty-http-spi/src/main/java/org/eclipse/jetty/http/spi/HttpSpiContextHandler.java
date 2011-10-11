package org.eclipse.jetty.http.spi;
//========================================================================
//Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
//========================================================================

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Authenticator.Result;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.ContextHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Jetty handler that bridges requests to {@link HttpHandler}.
 */
public class HttpSpiContextHandler extends ContextHandler
{

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
        if (!target.startsWith(getContextPath())) return;

        JettyHttpExchange jettyHttpExchange = new JettyHttpExchange(_httpContext, req, resp);

        // TODO: add filters processing

        try
        {
            Authenticator auth = _httpContext.getAuthenticator();
            if (auth != null)
                handleAuthentication(resp, jettyHttpExchange, auth);
            else
                _httpHandler.handle(jettyHttpExchange);
        }
        catch(Exception ex)
        {
            PrintWriter writer = new PrintWriter(jettyHttpExchange.getResponseBody());
            
            resp.setStatus(500);
            writer.println("<h2>HTTP ERROR: 500</h2>");
            writer.println("<pre>INTERNAL_SERVER_ERROR</pre>");
            writer.println("<p>RequestURI=" + req.getRequestURI() + "</p>");
            
            writer.println("<pre>");
            ex.printStackTrace(writer);
            writer.println("</pre>");
            
            writer.println("<p><i><small><a href=\"http://jetty.mortbay.org\">Powered by jetty://</a></small></i></p>");
            
            writer.close();
        }
        finally
        {
            Request base_request = (req instanceof Request) ? (Request)req:HttpConnection.getCurrentConnection().getRequest();
            base_request.setHandled(true);
        }
        
    }


    private void handleAuthentication(HttpServletResponse resp, JettyHttpExchange jettyHttpExchange, Authenticator auth) throws IOException
    {
        Result result = auth.authenticate(jettyHttpExchange);
        if (result instanceof Authenticator.Failure)
        {
            int rc = ((Authenticator.Failure)result).getResponseCode();
            resp.sendError(rc);
        }
        else if (result instanceof Authenticator.Retry)
        {
            int rc = ((Authenticator.Retry)result).getResponseCode();
            resp.sendError(rc);
        }
        else if (result instanceof Authenticator.Success)
        {
            HttpPrincipal principal = ((Authenticator.Success)result).getPrincipal();
            jettyHttpExchange.setPrincipal(principal);
            _httpHandler.handle(jettyHttpExchange);
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

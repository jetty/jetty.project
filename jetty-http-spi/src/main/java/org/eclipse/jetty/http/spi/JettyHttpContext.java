//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.spi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Jetty implementation of {@link com.sun.net.httpserver.HttpContext}
 */
public class JettyHttpContext extends com.sun.net.httpserver.HttpContext
{

    private HttpSpiContextHandler _jettyContextHandler;

    private HttpServer _server;

    private Map<String, Object> _attributes = new HashMap<String, Object>();

    private List<Filter> _filters = new ArrayList<Filter>();

    private Authenticator _authenticator;

    protected JettyHttpContext(HttpServer server, String path,
                               HttpHandler handler)
    {
        this._server = server;
        _jettyContextHandler = new HttpSpiContextHandler(this, handler);
        _jettyContextHandler.setContextPath(path);
    }

    protected HttpSpiContextHandler getJettyContextHandler()
    {
        return _jettyContextHandler;
    }

    @Override
    public HttpHandler getHandler()
    {
        return _jettyContextHandler.getHttpHandler();
    }

    @Override
    public void setHandler(HttpHandler h)
    {
        _jettyContextHandler.setHttpHandler(h);
    }

    @Override
    public String getPath()
    {
        return _jettyContextHandler.getContextPath();
    }

    @Override
    public HttpServer getServer()
    {
        return _server;
    }

    @Override
    public Map<String, Object> getAttributes()
    {
        return _attributes;
    }

    @Override
    public List<Filter> getFilters()
    {
        return _filters;
    }

    @Override
    public Authenticator setAuthenticator(Authenticator auth)
    {
        Authenticator previous = _authenticator;
        _authenticator = auth;
        return previous;
    }

    @Override
    public Authenticator getAuthenticator()
    {
        return _authenticator;
    }
}

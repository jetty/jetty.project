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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * Jetty implementation of {@link com.sun.net.httpserver.HttpServer}.
 */
public class JettyHttpServer extends com.sun.net.httpserver.HttpServer
{
    private static final Logger LOG = Log.getLogger(JettyHttpServer.class);

    private final HttpConfiguration _httpConfiguration;

    private final Server _server;

    private boolean _serverShared;

    private InetSocketAddress _addr;

    private Map<String, JettyHttpContext> _contexts = new HashMap<>();

    private Map<String, Connector> _connectors = new HashMap<>();

    public JettyHttpServer(Server server, boolean shared)
    {
        this(server, shared, new HttpConfiguration());
    }

    public JettyHttpServer(Server server, boolean shared, HttpConfiguration configuration)
    {
        this._server = server;
        this._serverShared = shared;
        this._httpConfiguration = configuration;
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _httpConfiguration;
    }

    @Override
    public void bind(InetSocketAddress addr, int backlog) throws IOException
    {
        this._addr = addr;
        // check if there is already a connector listening
        Collection<NetworkConnector> connectors = _server.getBeans(NetworkConnector.class);
        if (connectors != null)
        {
            for (NetworkConnector connector : connectors)
            {
                if (connector.getPort() == addr.getPort() || connector.getLocalPort() == addr.getPort())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("server already bound to port " + addr.getPort() + ", no need to rebind");
                    return;
                }
            }
        }

        if (_serverShared)
            throw new IOException("jetty server is not bound to port " + addr.getPort());

        if (LOG.isDebugEnabled())
            LOG.debug("binding server to port " + addr.getPort());
        ServerConnector connector = new ServerConnector(_server);
        connector.setPort(addr.getPort());
        connector.setHost(addr.getHostName());

        _server.addConnector(connector);

        _connectors.put(addr.getHostName() + addr.getPort(), connector);
    }

    protected Server getServer()
    {
        return _server;
    }

    protected ServerConnector newServerConnector(InetSocketAddress addr, int backlog)
    {
        ServerConnector connector = new ServerConnector(_server, new HttpConnectionFactory(_httpConfiguration));
        connector.setPort(addr.getPort());
        connector.setHost(addr.getHostName());
        return connector;
    }

    @Override
    public InetSocketAddress getAddress()
    {
        return _addr;
    }

    @Override
    public void start()
    {
        if (_serverShared)
            return;

        try
        {
            _server.start();
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void setExecutor(Executor executor)
    {
        if (executor == null)
            throw new IllegalArgumentException("missing required 'executor' argument");
        ThreadPool threadPool = _server.getThreadPool();
        if (threadPool instanceof DelegatingThreadPool)
        {
            try
            {
                if (_server.isRunning())
                {
                    _server.stop();
                }
                ((DelegatingThreadPool)_server.getThreadPool()).setExecutor(executor);
                _server.start();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        else
        {
            throw new UnsupportedOperationException("!DelegatingThreadPool");
        }
    }

    @Override
    public Executor getExecutor()
    {
        ThreadPool threadPool = _server.getThreadPool();
        if (threadPool instanceof DelegatingThreadPool)
            return ((DelegatingThreadPool)_server.getThreadPool()).getExecutor();
        return threadPool;
    }

    @Override
    public void stop(int delay)
    {
        cleanUpContexts();
        cleanUpConnectors();

        if (_serverShared)
            return;

        try
        {
            _server.stop();
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    private void cleanUpContexts()
    {
        for (Map.Entry<String, JettyHttpContext> stringJettyHttpContextEntry : _contexts.entrySet())
        {
            JettyHttpContext context = stringJettyHttpContextEntry.getValue();
            _server.removeBean(context.getJettyContextHandler());
        }
        _contexts.clear();
    }

    private void cleanUpConnectors()
    {
        for (Map.Entry<String, Connector> stringConnectorEntry : _connectors.entrySet())
        {
            Connector connector = stringConnectorEntry.getValue();
            try
            {
                connector.stop();
            }
            catch (Exception ex)
            {
                LOG.warn(ex);
            }
            _server.removeConnector(connector);
        }
        _connectors.clear();
    }

    @Override
    public HttpContext createContext(String path, HttpHandler httpHandler)
    {
        checkIfContextIsFree(path);

        JettyHttpContext context = new JettyHttpContext(this, path, httpHandler);
        HttpSpiContextHandler jettyContextHandler = context.getJettyContextHandler();

        ContextHandlerCollection chc = findContextHandlerCollection(_server.getHandlers());
        if (chc == null)
            throw new RuntimeException("could not find ContextHandlerCollection, you must configure one");

        chc.addHandler(jettyContextHandler);
        _contexts.put(path, context);

        if (!jettyContextHandler.isStarted())
        {
            try
            {
                jettyContextHandler.start();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        return context;
    }

    private ContextHandlerCollection findContextHandlerCollection(Handler[] handlers)
    {
        if (handlers == null)
            return null;

        for (Handler handler : handlers)
        {
            if (handler instanceof ContextHandlerCollection)
            {
                return (ContextHandlerCollection)handler;
            }

            if (handler instanceof HandlerCollection)
            {
                HandlerCollection hc = (HandlerCollection)handler;
                ContextHandlerCollection chc = findContextHandlerCollection(hc.getHandlers());
                if (chc != null)
                    return chc;
            }
        }
        return null;
    }

    private void checkIfContextIsFree(String path)
    {
        Handler serverHandler = _server.getHandler();
        if (serverHandler instanceof ContextHandler)
        {
            ContextHandler ctx = (ContextHandler)serverHandler;
            if (ctx.getContextPath().equals(path))
                throw new RuntimeException("another context already bound to path " + path);
        }

        Handler[] handlers = _server.getHandlers();
        if (handlers == null)
            return;

        for (Handler handler : handlers)
        {
            if (handler instanceof ContextHandler)
            {
                ContextHandler ctx = (ContextHandler)handler;
                if (ctx.getContextPath().equals(path))
                    throw new RuntimeException("another context already bound to path " + path);
            }
        }
    }

    @Override
    public HttpContext createContext(String path)
    {
        return createContext(path, null);
    }

    @Override
    public void removeContext(String path) throws IllegalArgumentException
    {
        JettyHttpContext context = _contexts.remove(path);
        if (context == null)
            return;
        _server.removeBean(context.getJettyContextHandler());
    }

    @Override
    public void removeContext(HttpContext context)
    {
        removeContext(context.getPath());
    }
}

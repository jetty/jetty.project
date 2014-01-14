//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;


/**
 * AbstractTestServer
 *
 *
 */
public abstract class AbstractTestServer
{
    protected final Server _server;
    protected final int _maxInactivePeriod;
    protected final int _scavengePeriod;
    protected final ContextHandlerCollection _contexts;
    protected SessionIdManager _sessionIdManager;

    
    
    public AbstractTestServer(int port)
    {
        this(port, 30, 10);
    }

    public AbstractTestServer(int port, int maxInactivePeriod, int scavengePeriod)
    {
        this (port, maxInactivePeriod, scavengePeriod, null);
    }
    
    public AbstractTestServer(int port, int maxInactivePeriod, int scavengePeriod, String sessionIdMgrConfig)
    {
        _server = new Server(port);
        _maxInactivePeriod = maxInactivePeriod;
        _scavengePeriod = scavengePeriod;
        _contexts = new ContextHandlerCollection();
        _sessionIdManager = newSessionIdManager(sessionIdMgrConfig);
        _server.setSessionIdManager(_sessionIdManager);
    }
    
    

    public abstract SessionIdManager newSessionIdManager(String config);
    public abstract SessionManager newSessionManager();
    public abstract SessionHandler newSessionHandler(SessionManager sessionManager);


    public void start() throws Exception
    {
        // server -> contexts collection -> context handler -> session handler -> servlet handler
        _server.setHandler(_contexts);
        _server.start();
    }
    
    public int getPort()
    {
        return ((NetworkConnector)getServer().getConnectors()[0]).getLocalPort();
    }

    public ServletContextHandler addContext(String contextPath)
    {
        ServletContextHandler context = new ServletContextHandler(_contexts, contextPath);

        SessionManager sessionManager = newSessionManager();
        sessionManager.setSessionIdManager(_sessionIdManager);
        sessionManager.setMaxInactiveInterval(_maxInactivePeriod);

        SessionHandler sessionHandler = newSessionHandler(sessionManager);
        sessionManager.setSessionHandler(sessionHandler);
        context.setSessionHandler(sessionHandler);

        return context;
    }

    public void stop() throws Exception
    {
        _server.stop();
    }

    public void join() throws Exception
    {
        _server.join();
    }

    public WebAppContext addWebAppContext(String warPath, String contextPath)
    {
        WebAppContext context = new WebAppContext(_contexts, warPath, contextPath);

        SessionManager sessionManager = newSessionManager();
        sessionManager.setSessionIdManager(_sessionIdManager);
        sessionManager.setMaxInactiveInterval(_maxInactivePeriod);

        SessionHandler sessionHandler = newSessionHandler(sessionManager);
        sessionManager.setSessionHandler(sessionHandler);
        context.setSessionHandler(sessionHandler);

        return context;
    }
    
    public Server getServer()
    {
        return _server;
    }
}

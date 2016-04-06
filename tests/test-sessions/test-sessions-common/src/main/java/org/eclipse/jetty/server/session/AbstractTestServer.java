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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.session.SessionManager;
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
    public static int DEFAULT_MAX_INACTIVE = 30;
 
    public static int DEFAULT_SCAVENGE_SEC = 10;
    public static int DEFAULT_IDLE_PASSIVATE_SEC = 2;
    
    protected static int __workers=0;
    
    protected final Server _server;
    protected final int _maxInactivePeriod;
    protected final int _idlePassivatePeriod;
    protected final int _scavengePeriod;
    protected final ContextHandlerCollection _contexts;
    protected SessionIdManager _sessionIdManager;
    private HouseKeeper _housekeeper;
    protected Object _config;

  
    
    public static String extractSessionId (String sessionCookie)
    {
        if (sessionCookie == null)
            return null;
        sessionCookie = sessionCookie.trim();
        int i = sessionCookie.indexOf(';');
        if (i >= 0)
            sessionCookie = sessionCookie.substring(0,i);
        if (sessionCookie.startsWith("JSESSIONID"))
            sessionCookie = sessionCookie.substring("JSESSIONID=".length());
        i = sessionCookie.indexOf('.');
        if (i >=0)
            sessionCookie = sessionCookie.substring(0,i);
        return sessionCookie;
    }

    
    
    public AbstractTestServer(int port)
    {
        this(port, DEFAULT_MAX_INACTIVE, DEFAULT_SCAVENGE_SEC, DEFAULT_IDLE_PASSIVATE_SEC);
    }

    public AbstractTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod)
    {
        this (port, maxInactivePeriod, scavengePeriod, idlePassivatePeriod, null);
    }
    
    public AbstractTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod, Object cfg)
    {
        _server = new Server(port);
        _maxInactivePeriod = maxInactivePeriod;
        _scavengePeriod = scavengePeriod;
        _idlePassivatePeriod = idlePassivatePeriod;
        _contexts = new ContextHandlerCollection();
        _config = cfg;
        _sessionIdManager = newSessionIdManager();
        _server.setSessionIdManager(_sessionIdManager);
        ((DefaultSessionIdManager) _sessionIdManager).setServer(_server);
        _housekeeper = new HouseKeeper();
        _housekeeper.setIntervalSec(_scavengePeriod);
        ((DefaultSessionIdManager)_sessionIdManager).setSessionInspector(_housekeeper);
    }
    


    /**
     * @return
     */
    public SessionIdManager newSessionIdManager()
    {
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(getServer());
        idManager.setWorkerName("w"+(__workers++));
        return idManager;
    }


    public abstract SessionManager newSessionManager();
    public abstract SessionHandler newSessionHandler(SessionManager sessionManager);


    public void start() throws Exception
    {
        // server -> contexts collection -> context handler -> session handler -> servlet handler
        _server.setHandler(_contexts);
        _server.start();
    }
    
    public HouseKeeper getInspector()
    {
        return _housekeeper;
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
        sessionManager.getSessionStore().setIdlePassivationTimeoutSec(_idlePassivatePeriod);
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
        
        sessionManager.getSessionStore().setIdlePassivationTimeoutSec(_idlePassivatePeriod);

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

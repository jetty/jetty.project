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

package org.eclipse.jetty.session;

import org.eclipse.jetty.ee9.nested.SessionHandler;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;

public class SessionTestSupport
{
    public static int DEFAULT_MAX_INACTIVE = 30;
    public static int DEFAULT_SCAVENGE_SEC = 10;
    public static int DEFAULT_EVICTIONPOLICY = SessionCache.NEVER_EVICT;

    protected static int __workers = 0;

    protected final Server _server;
    protected final int _maxInactivePeriod;
    protected final int _scavengePeriod;
    protected final ContextHandlerCollection _contexts;
    protected SessionIdManager _sessionIdManager;
    private HouseKeeper _housekeeper;
    protected Object _config;
    protected SessionCacheFactory _cacheFactory;
    protected SessionDataStoreFactory _storeFactory;

    public static String extractSessionId(String sessionCookie)
    {
        if (sessionCookie == null)
            return null;
        sessionCookie = sessionCookie.trim();
        int i = sessionCookie.indexOf(';');
        if (i >= 0)
            sessionCookie = sessionCookie.substring(0, i);
        if (sessionCookie.startsWith("JSESSIONID"))
            sessionCookie = sessionCookie.substring("JSESSIONID=".length());
        i = sessionCookie.indexOf('.');
        if (i >= 0)
            sessionCookie = sessionCookie.substring(0, i);
        return sessionCookie;
    }

    public SessionTestSupport(int port, int maxInactivePeriod, int scavengePeriod, SessionCacheFactory cacheFactory, SessionDataStoreFactory storeFactory) throws Exception
    {
        _server = new Server(port);
        _maxInactivePeriod = maxInactivePeriod;
        _scavengePeriod = scavengePeriod;
        _cacheFactory = cacheFactory;
        _storeFactory = storeFactory;
        _contexts = new ContextHandlerCollection();
        _sessionIdManager = newSessionIdManager();
        _server.addBean(_sessionIdManager, true);
        ((DefaultSessionIdManager)_sessionIdManager).setServer(_server);
        _housekeeper = new HouseKeeper();
        _housekeeper.setIntervalSec(_scavengePeriod);
        ((DefaultSessionIdManager)_sessionIdManager).setSessionHouseKeeper(_housekeeper);
    }

    public SessionIdManager newSessionIdManager()
    {
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(getServer());
        idManager.setWorkerName("w" + (__workers++));
        return idManager;
    }

    public SessionHandler newSessionHandler()
        throws Exception
    {
        SessionHandler h = new SessionHandler();
        SessionCache c = _cacheFactory.getSessionCache(h.getSessionManager());
        SessionDataStore s = _storeFactory.getSessionDataStore(h.getSessionManager());
        c.setSessionDataStore(s);
        h.getSessionManager().setSessionCache(c);
        return h;
    }

    public ServerConnector getServerConnector()
    {
        return _server.getBean(ServerConnector.class);
    }

    public void start() throws Exception
    {
        // server -> contexts collection -> context handler -> session handler -> servlet handler
        _server.setHandler(_contexts);
        _server.start();
    }

    public HouseKeeper getHouseKeeper()
    {
        return _housekeeper;
    }

    public int getPort()
    {
        return ((NetworkConnector)getServer().getConnectors()[0]).getLocalPort();
    }

    public ServletContextHandler addContext(String contextPath) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(_contexts, contextPath);
        SessionHandler sessionHandler = newSessionHandler();
        sessionHandler.getSessionManager().setSessionIdManager(_sessionIdManager);
        sessionHandler.setMaxInactiveInterval(_maxInactivePeriod);
        context.setSessionHandler(sessionHandler);

        return context;
    }

    public void stop() throws Exception
    {
        _server.stop();
    }

    public WebAppContext addWebAppContext(String warPath, String contextPath) throws Exception
    {
        WebAppContext context = new WebAppContext(warPath, contextPath);
        _contexts.addHandler(context);
        SessionHandler sessionHandler = newSessionHandler();
        sessionHandler.getSessionManager().setSessionIdManager(_sessionIdManager);
        sessionHandler.setMaxInactiveInterval(_maxInactivePeriod);
        context.setSessionHandler(sessionHandler);

        return context;
    }

    public Server getServer()
    {
        return _server;
    }
}

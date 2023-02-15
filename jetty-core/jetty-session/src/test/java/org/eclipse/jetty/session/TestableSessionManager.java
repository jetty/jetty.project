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

package org.eclipse.jetty.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.Session.API;
import org.eclipse.jetty.util.StringUtil;

/**
 * TestSessionHandler
 *
 * For testing convenience.
 */
public class TestableSessionManager extends AbstractSessionManager
{
    private Server _server;

    java.util.Collection<String> _sessionIdListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionCreatedListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionDestroyedListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionAttributeListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionUnboundListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionBoundListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionActivationListenersCalled = new ArrayList<>();
    java.util.Collection<String> _sessionPassivationListenersCalled = new ArrayList<>();
    java.util.Collection<String> _expiredIds = new ArrayList<>();

    protected Map<String, String> _cookieConfig = new HashMap<>();

    public void setServer(Server server)
    {
        _server = server;
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    public void clear()
    {
        _sessionIdListenersCalled.clear();
        _sessionCreatedListenersCalled.clear();
        _sessionDestroyedListenersCalled.clear();
        _sessionAttributeListenersCalled.clear();
        _sessionUnboundListenersCalled.clear();
        _sessionBoundListenersCalled.clear();
        _sessionActivationListenersCalled.clear();
        _sessionPassivationListenersCalled.clear();
        _expiredIds.clear();
    }

    @Override
    public API newSessionAPIWrapper(ManagedSession session)
    {
        return new API()
        {
            @Override
            public ManagedSession getSession()
            {
                return session;
            }

            public String getId()
            {
                return session.getId();
            }
        };
    }

    @Override
    public ManagedSession getManagedSession(Request request)
    {
        return null;
    }

    @Override
    public void callSessionIdListeners(Session session, String oldId)
    {
        _sessionIdListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionCreatedListeners(Session session)
    {
        _sessionCreatedListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionDestroyedListeners(Session session)
    {
        _sessionDestroyedListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionAttributeListeners(Session session, String name, Object old, Object value)
    {
        _sessionAttributeListenersCalled.add(session.getId());
    }

    @Override
    public void callUnboundBindingListener(Session session, String name, Object value)
    {
        _sessionUnboundListenersCalled.add(session.getId());
    }

    @Override
    public void callBoundBindingListener(Session session, String name, Object value)
    {
        _sessionBoundListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionActivationListener(Session session, String name, Object value)
    {
        _sessionActivationListenersCalled.add(session.getId());
    }

    @Override
    public void callSessionPassivationListener(Session session, String name, Object value)
    {
        _sessionPassivationListenersCalled.add(session.getId());
    }

    protected void configureCookies()
    {
        String cookieName = _cookieConfig.get(__SessionCookieProperty);
        if (StringUtil.isNotBlank(cookieName))
            setSessionCookie(cookieName);

        String paramName = _cookieConfig.get(__SessionIdPathParameterNameProperty);
        if (StringUtil.isNotBlank(paramName))
            setSessionIdPathParameterName(paramName);

        // set up the max session cookie age
        String maxAge = _cookieConfig.get(__MaxAgeProperty);
        if (StringUtil.isNotBlank(maxAge))
            setMaxCookieAge(Integer.parseInt(maxAge));

        // set up the session domain
        String domain = _cookieConfig.get(__SessionDomainProperty);
        if (StringUtil.isNotBlank(domain))
            setSessionDomain(domain);

        // set up the sessionPath
        String path = _cookieConfig.get(__SessionPathProperty);
        if (StringUtil.isNotBlank(path))
            setSessionPath(path);

        String checkEncoding = _cookieConfig.get(__CheckRemoteSessionEncoding);
        if (StringUtil.isNotBlank(checkEncoding))
            setCheckingRemoteSessionIdEncoding(Boolean.parseBoolean(checkEncoding));
    }
    
    public Map<String, String> getCookieConfig()
    {
        return _cookieConfig;
    }
}

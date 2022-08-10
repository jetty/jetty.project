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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.Session.APISession;
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
    public APISession newSessionAPIWrapper(Session session)
    {
        return new APISession()
        {
            @Override
            public Session getCoreSession()
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
    public Session getSession(Request request)
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
        String tmp = _cookieConfig.get(__SessionCookieProperty);
        if (tmp != null)
            setSessionCookie(tmp);

        tmp = _cookieConfig.get(__SessionIdPathParameterNameProperty);
        if (tmp != null)
            setSessionIdPathParameterName(tmp);

        // set up the max session cookie age if it isn't already
        if (getMaxCookieAge() == -1)
        {
            tmp = _cookieConfig.get(__MaxAgeProperty);
            if (tmp != null)
                setMaxCookieAge(Integer.parseInt(tmp.trim()));
        }

        // set up the session domain if it isn't already
        if (getSessionDomain() == null)
            setSessionDomain(_cookieConfig.get(__SessionDomainProperty));

        // set up the sessionPath if it isn't already
        if (getSessionPath() == null)
            setSessionPath(_cookieConfig.get(__SessionPathProperty));

        tmp = _cookieConfig.get(__CheckRemoteSessionEncoding);
        if (tmp != null)
            setCheckingRemoteSessionIdEncoding(Boolean.parseBoolean(tmp));
    }
    
    public Map<String, String> getCookieConfig()
    {
        return _cookieConfig;
    }

    @Override
    public HttpCookie getSessionCookie(Session session, String contextPath, boolean requestIsSecure)
    {
        if (isUsingCookies())
        {
            String sessionPath = getSessionPath();
            sessionPath = (sessionPath == null) ? contextPath : sessionPath;
            sessionPath = (StringUtil.isEmpty(sessionPath)) ? "/" : sessionPath;
            SameSite sameSite = HttpCookie.getSameSiteFromComment(getSessionComment());
            Map<String, String> attributes = Collections.emptyMap();
            if (sameSite != null)
                attributes = Collections.singletonMap("SameSite", sameSite.getAttributeValue());
            return session.generateSetCookie((getSessionCookie() == null ? __DefaultSessionCookie : getSessionCookie()),
                getSessionDomain(),
                sessionPath,
                getMaxCookieAge(),
                isHttpOnly(),
                isSecureCookies() || (isSecureRequestOnly() && requestIsSecure),
                HttpCookie.getCommentWithoutAttributes(getSessionComment()),
                0,
                attributes);
        }
        return null;
    }

    @Override
    public SameSite getSameSite()
    {
        return HttpCookie.getSameSiteFromComment(getSessionComment());
    }

    @Override
    public void setSameSite(SameSite sameSite)
    {
        setSessionComment(HttpCookie.getCommentWithAttributes(getSessionComment(), false, sameSite));
    }
}

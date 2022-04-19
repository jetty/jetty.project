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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionContext;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionConfig;
import org.eclipse.jetty.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionHandler extends HandlerWrapper implements SessionConfig.Mutable
{
    static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);

    public static final EnumSet<SessionTrackingMode> DEFAULT_SESSION_TRACKING_MODES = EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);

    private final CoreSessionManager _sessionManager = new CoreSessionManager();
    private final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionListener> _sessionListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<>();
    private final SessionCookieConfig _cookieConfig = new SessionHandler.CookieConfig();

    private ContextHandler _contextHandler;

    public SessionHandler()
    {
        setSessionTrackingModes(DEFAULT_SESSION_TRACKING_MODES);
        addBean(_sessionManager);
        addBean(_cookieConfig);
        addBean(_sessionListeners);
        addBean(_sessionIdListeners);
        addBean(_sessionAttributeListeners);
    }

    public SessionManager getSessionManager()
    {
        return _sessionManager;
    }

    protected List<HttpSessionListener> getSessionListeners()
    {
        return _sessionListeners;
    }

    protected List<HttpSessionAttributeListener> getSessionAttributeListeners()
    {
        return _sessionAttributeListeners;
    }

    protected List<HttpSessionIdListener> getSessionIdListeners()
    {
        return _sessionIdListeners;
    }

    @Override
    protected void doStart() throws Exception
    {
        _contextHandler = ContextHandler.getCurrentContext().getContextHandler();
        super.doStart();
    }

    /**
     * Adds an event listener for session-related events.
     *
     * @param listener the session event listener to add
     * Individual SessionManagers implementations may accept arbitrary listener types,
     * but they are expected to at least handle HttpSessionActivationListener,
     * HttpSessionAttributeListener, HttpSessionBindingListener and HttpSessionListener.
     * @return true if the listener was added
     * @see #removeEventListener(EventListener)
     * @see HttpSessionAttributeListener
     * @see HttpSessionListener
     * @see HttpSessionIdListener
     */
    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (super.addEventListener(listener))
        {
            if (listener instanceof HttpSessionAttributeListener)
                _sessionAttributeListeners.add((HttpSessionAttributeListener)listener);
            if (listener instanceof HttpSessionListener)
                _sessionListeners.add((HttpSessionListener)listener);
            if (listener instanceof HttpSessionIdListener)
                _sessionIdListeners.add((HttpSessionIdListener)listener);
            return true;
        }
        return false;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if (listener instanceof HttpSessionAttributeListener)
                _sessionAttributeListeners.remove(listener);
            if (listener instanceof HttpSessionListener)
                _sessionListeners.remove(listener);
            if (listener instanceof HttpSessionIdListener)
                _sessionIdListeners.remove(listener);
            return true;
        }
        return false;
    }

    public SessionCookieConfig getSessionCookieConfig()
    {
        return _cookieConfig;
    }

    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        return DEFAULT_SESSION_TRACKING_MODES;
    }

    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        if (_sessionManager.isUsingCookies())
        {
            if (_sessionManager.isUsingURLs())
                return Set.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
            return Set.of(SessionTrackingMode.COOKIE);
        }

        if (_sessionManager.isUsingURLs())
            return Set.of(SessionTrackingMode.URL);

        return Collections.emptySet();
    }

    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        if (sessionTrackingModes != null &&
            sessionTrackingModes.size() > 1 &&
            sessionTrackingModes.contains(SessionTrackingMode.SSL))
        {
            throw new IllegalArgumentException("sessionTrackingModes.SSL is not supported");
        }
        _sessionManager.setUsingCookies(sessionTrackingModes != null && sessionTrackingModes.contains(SessionTrackingMode.COOKIE));
        _sessionManager.setUsingURLs(sessionTrackingModes != null && sessionTrackingModes.contains(SessionTrackingMode.URL));
    }

    @Override
    public int getMaxCookieAge()
    {
        return _sessionManager.getMaxCookieAge();
    }

    @Override
    public int getMaxInactiveInterval()
    {
        return _sessionManager.getMaxInactiveInterval();
    }

    @Override
    public int getRefreshCookieAge()
    {
        return _sessionManager.getRefreshCookieAge();
    }

    @Override
    public HttpCookie.SameSite getSameSite()
    {
        return _sessionManager.getSameSite();
    }

    @Override
    public String getSessionComment()
    {
        return _sessionManager.getSessionComment();
    }

    @Override
    public String getSessionCookie()
    {
        return _sessionManager.getSessionCookie();
    }

    @Override
    public String getSessionDomain()
    {
        return _sessionManager.getSessionDomain();
    }

    @Override
    public String getSessionIdPathParameterName()
    {
        return _sessionManager.getSessionIdPathParameterName();
    }

    @Override
    public String getSessionIdPathParameterNamePrefix()
    {
        return _sessionManager.getSessionIdPathParameterNamePrefix();
    }

    @Override
    public String getSessionPath()
    {
        return _sessionManager.getSessionPath();
    }

    @Override
    public boolean isCheckingRemoteSessionIdEncoding()
    {
        return _sessionManager.isCheckingRemoteSessionIdEncoding();
    }

    @Override
    public boolean isHttpOnly()
    {
        return _sessionManager.isHttpOnly();
    }

    @Override
    public boolean isSecureCookies()
    {
        return _sessionManager.isSecureCookies();
    }

    @Override
    public boolean isSecureRequestOnly()
    {
        return _sessionManager.isSecureRequestOnly();
    }

    @Override
    public boolean isUsingCookies()
    {
        return _sessionManager.isUsingCookies();
    }

    @Override
    public boolean isUsingURLs()
    {
        return _sessionManager.isUsingURLs();
    }

    @Override
    public void setCheckingRemoteSessionIdEncoding(boolean value)
    {
        _sessionManager.setCheckingRemoteSessionIdEncoding(value);
    }

    @Override
    public void setHttpOnly(boolean value)
    {
        _sessionManager.setHttpOnly(value);
    }

    @Override
    public void setMaxCookieAge(int value)
    {
        _sessionManager.setMaxCookieAge(value);
    }

    @Override
    public void setMaxInactiveInterval(int value)
    {
        _sessionManager.setMaxInactiveInterval(value);
    }

    @Override
    public void setRefreshCookieAge(int value)
    {
        _sessionManager.setRefreshCookieAge(value);
    }

    @Override
    public void setSameSite(HttpCookie.SameSite sameSite)
    {
        _sessionManager.setSameSite(sameSite);
    }

    @Override
    public void setSecureCookies(boolean value)
    {
        _sessionManager.setSecureCookies(value);
    }

    @Override
    public void setSecureRequestOnly(boolean value)
    {
        _sessionManager.setSecureRequestOnly(value);
    }

    @Override
    public void setSessionComment(String sessionComment)
    {
        _sessionManager.setSessionComment(sessionComment);
    }

    @Override
    public void setSessionCookie(String value)
    {
        _sessionManager.setSessionCookie(value);
    }

    @Override
    public void setSessionDomain(String value)
    {
        _sessionManager.setSessionDomain(value);
    }

    @Override
    public void setSessionIdPathParameterName(String value)
    {
        _sessionManager.setSessionIdPathParameterName(value);
    }

    @Override
    public void setSessionPath(String value)
    {
        _sessionManager.setSessionPath(value);
    }

    @Override
    public void setUsingCookies(boolean value)
    {
        _sessionManager.setUsingCookies(value);
    }

    @Override
    public void setUsingURLs(boolean value)
    {
        _sessionManager.setUsingURLs(value);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (baseRequest.getDispatcherType() == DispatcherType.REQUEST)
        {
            org.eclipse.jetty.server.Request coreRequest = baseRequest.getHttpChannel().getCoreRequest();

            // find and set the session if one exists
            AbstractSessionManager.RequestedSession requestedSession = _sessionManager.resolveRequestedSessionId(coreRequest);

            baseRequest.setCoreSession(requestedSession.session());
            baseRequest.setSessionManager(_sessionManager);
            baseRequest.setRequestedSessionId(requestedSession.sessionId());
            baseRequest.setRequestedSessionIdFromCookie(requestedSession.sessionIdFromCookie());

            HttpCookie cookie = _sessionManager.access(requestedSession.session(), coreRequest.getConnectionMetaData().isSecure());

            // Handle changed ID or max-age refresh, but only if this is not a redispatched request
            if (cookie != null)
                baseRequest.getResponse().replaceCookie(cookie);
        }
        super.handle(target, baseRequest, request, response);
    }

    /**
     * CookieConfig
     *
     * Implementation of the jakarta.servlet.SessionCookieConfig.
     * SameSite configuration can be achieved by using setComment
     *
     * @see HttpCookie
     */
    public final class CookieConfig implements SessionCookieConfig
    {
        @Override
        public String getComment()
        {
            return _sessionManager.getSessionComment();
        }

        @Override
        public String getDomain()
        {
            return _sessionManager.getSessionDomain();
        }

        @Override
        public int getMaxAge()
        {
            return _sessionManager.getMaxCookieAge();
        }

        @Override
        public String getName()
        {
            return _sessionManager.getSessionCookie();
        }

        @Override
        public String getPath()
        {
            return _sessionManager.getSessionPath();
        }

        @Override
        public boolean isHttpOnly()
        {
            return _sessionManager.isHttpOnly();
        }

        @Override
        public boolean isSecure()
        {
            return _sessionManager.isSecureCookies();
        }

        private void checkAvailable()
        {
            if (_contextHandler != null && _contextHandler.isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
        }

        @Override
        public void setComment(String comment)
        {
            checkAvailable();
            _sessionManager.setSessionComment(comment);
        }

        @Override
        public void setDomain(String domain)
        {
            checkAvailable();
            _sessionManager.setSessionDomain(domain);
        }

        @Override
        public void setHttpOnly(boolean httpOnly)
        {
            checkAvailable();
            _sessionManager.setHttpOnly(httpOnly);
        }

        @Override
        public void setMaxAge(int maxAge)
        {
            checkAvailable();
            _sessionManager.setMaxCookieAge(maxAge);
        }

        @Override
        public void setName(String name)
        {
            checkAvailable();
            _sessionManager.setSessionCookie(name);
        }

        @Override
        public void setPath(String path)
        {
            checkAvailable();
            _sessionManager.setSessionPath(path);
        }

        @Override
        public void setSecure(boolean secure)
        {
            checkAvailable();
            _sessionManager.setSecureCookies(secure);
        }
    }

    private class CoreSessionManager extends AbstractSessionManager
    {
        @Override
        public Server getServer()
        {
            return SessionHandler.this.getServer();
        }

        @Override
        public Session getSession(org.eclipse.jetty.server.Request request)
        {
            return org.eclipse.jetty.server.Request.get(request, ContextHandler.CoreContextRequest.class, ContextHandler.CoreContextRequest::getHttpChannel)
                .getRequest().getCoreSession();
        }

        @Override
        public Session.APISession newSessionAPIWrapper(Session session)
        {
            return new ServletAPISession(session);
        }

        @Override
        protected RequestedSession resolveRequestedSessionId(org.eclipse.jetty.server.Request request)
        {
            return super.resolveRequestedSessionId(request);
        }

        @Override
        public void callSessionAttributeListeners(Session session, String name, Object old, Object value)
        {
            if (!_sessionAttributeListeners.isEmpty())
            {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(session.getAPISession(), name, old == null ? value : old);

                for (HttpSessionAttributeListener l : _sessionAttributeListeners)
                {
                    if (old == null)
                        l.attributeAdded(event);
                    else if (value == null)
                        l.attributeRemoved(event);
                    else
                        l.attributeReplaced(event);
                }
            }
        }

        /**
         * Call the session lifecycle listeners in the order
         * they were added.
         *
         * @param session the session on which to call the lifecycle listeners
         */
        @Override
        public void callSessionCreatedListeners(Session session)
        {
            if (session == null)
                return;

            HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
            for (HttpSessionListener  l : _sessionListeners)
                l.sessionCreated(event);
        }

        /**
         * Call the session lifecycle listeners in
         * the reverse order they were added.
         *
         * @param session the session on which to call the lifecycle listeners
         */
        @Override
        public void callSessionDestroyedListeners(Session session)
        {
            if (session == null)
                return;

            //We annoint the calling thread with
            //the webapp's classloader because the calling thread may
            //come from the scavenger, rather than a request thread
            Runnable r = () ->
            {
                HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
                for (int i = _sessionListeners.size() - 1; i >= 0; i--)
                {
                    _sessionListeners.get(i).sessionDestroyed(event);
                }
            };
            _contextHandler.getCoreContextHandler().getContext().run(r);
        }

        @Override
        public void callSessionIdListeners(Session session, String oldId)
        {
            //inform the listeners
            if (!_sessionIdListeners.isEmpty())
            {
                HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
                for (HttpSessionIdListener l : _sessionIdListeners)
                {
                    l.sessionIdChanged(event, oldId);
                }
            }
        }

        @Override
        public void callUnboundBindingListener(Session session, String name, Object value)
        {
            if (value instanceof HttpSessionBindingListener)
                ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(session.getAPISession(), name));
        }

        @Override
        public void callBoundBindingListener(Session session, String name, Object value)
        {
            if (value instanceof HttpSessionBindingListener)
                ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(session.getAPISession(), name));
        }

        @Override
        public void callSessionActivationListener(Session session, String name, Object value)
        {
            if (value instanceof HttpSessionActivationListener listener)
            {
                HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
                listener.sessionDidActivate(event);
            }

        }

        @Override
        public void callSessionPassivationListener(Session session, String name, Object value)
        {
            if (value instanceof HttpSessionActivationListener listener)
            {
                HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
                listener.sessionWillPassivate(event);
            }
        }
    }

    public class ServletAPISession implements HttpSession, Session.APISession
    {
        private final Session _session;

        private ServletAPISession(Session session)
        {
            _session = session;
        }

        @Override
        public Session getCoreSession()
        {
            return _session;
        }

        @Override
        public long getCreationTime()
        {
            return _session.getCreationTime();
        }

        @Override
        public String getId()
        {
            return _session.getId();
        }

        @Override
        public long getLastAccessedTime()
        {
            return _session.getLastAccessedTime();
        }

        @Override
        public ServletContext getServletContext()
        {
            return _contextHandler.getServletContext();
        }

        @Override
        public void setMaxInactiveInterval(int interval)
        {
            _session.setMaxInactiveInterval(interval);
        }

        @Override
        public int getMaxInactiveInterval()
        {
            return _session.getMaxInactiveInterval();
        }

        @Override
        public Object getAttribute(String name)
        {
            return _session.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames()
        {
            return Collections.enumeration(_session.getNames());
        }

        @Override
        public void setAttribute(String name, Object value)
        {
            _session.setAttribute(name, value);
        }

        @Override
        public void removeAttribute(String name)
        {
            _session.removeAttribute(name);
        }

        @Override
        public void invalidate()
        {
            _session.invalidate();
        }

        @Override
        public boolean isNew()
        {
            return _session.isNew();
        }

        @Override
        public HttpSessionContext getSessionContext()
        {
            return null;
        }

        @Override
        public Object getValue(String name)
        {
            return getAttribute(name);
        }

        @Override
        public String[] getValueNames()
        {
            return _session.getNames().toArray(new String[0]);
        }

        @Override
        public void putValue(String name, Object value)
        {
            setAttribute(name, value);
        }

        @Override
        public void removeValue(String name)
        {
            removeAttribute(name);
        }
    }
}

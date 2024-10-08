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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
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
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionConfig;
import org.eclipse.jetty.session.SessionIdManager;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionHandler extends ScopedHandler implements SessionConfig.Mutable
{
    static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);

    public static final EnumSet<SessionTrackingMode> DEFAULT_SESSION_TRACKING_MODES = EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);

    private final CoreSessionManager _sessionManager = new CoreSessionManager();
    private final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionListener> _sessionListeners = new CopyOnWriteArrayList<>();
    private final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<>();
    private final SessionCookieConfig _cookieConfig = new SessionHandler.CookieConfig();

    private ContextHandler _contextHandler;

    /**
     * Wrapper to ensure the correct lifecycle for all sessions in all
     * contexts to which the request has been dispatched. When a response
     * is about to be sent back to the client, all of the sessions are
     * given the opportunity to persist themselves. When the response is
     * finished being handled, then the sessions have their reference
     * counts decremented, potentially leading to eviction from their cache,
     * as appropriate to the configuration of that cache.
     *
     * This wrapper replaces the AbstractSessionManager.SessionStreamWrapper.
     */
    private static class SessionStreamWrapper extends HttpStream.Wrapper
    {
        private final ContextHandler.CoreContextRequest _request;
        private final org.eclipse.jetty.server.Context _context;

        public SessionStreamWrapper(HttpStream wrapped, ContextHandler.CoreContextRequest request)
        {
            super(wrapped);
            _request = request;
            _context = _request.getContext();
        }

        @Override
        public void failed(Throwable x)
        {
             _request.completeSessions();
            super.failed(x);
        }

        @Override
        public void send(MetaData.Request metadataRequest, MetaData.Response metadataResponse, boolean last, ByteBuffer content, Callback callback)
        {
            if (metadataResponse != null)
            {
                // Write out all sessions
                _request.commitSessions();
            }
            super.send(metadataRequest, metadataResponse, last, content, callback);
        }

        @Override
        public void succeeded()
        {
            // Decrement usage count on all sessions
             _request.completeSessions();
            super.succeeded();
        }
    }

    public SessionHandler()
    {
        setSessionTrackingModes(DEFAULT_SESSION_TRACKING_MODES);
        installBean(_sessionManager);
        installBean(_cookieConfig);
        installBean(_sessionListeners);
        installBean(_sessionIdListeners);
        installBean(_sessionAttributeListeners);
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
    public void setSessionCache(SessionCache cache)
    {
        _sessionManager.setSessionCache(cache);
    }

    public SessionCache getSessionCache()
    {
        return _sessionManager.getSessionCache();
    }

    @Override
    public void setSessionIdManager(SessionIdManager sessionIdManager)
    {
        _sessionManager.setSessionIdManager(sessionIdManager);
    }

    public SessionIdManager getSessionIdManager()
    {
        return _sessionManager.getSessionIdManager();
    }

    protected void configureCookies()
    {
        if (_contextHandler == null)
            return;

        //configure the name of the session cookie set by an init param
        String tmp = _contextHandler.getInitParameter(SessionConfig.__SessionCookieProperty);
        if (tmp != null)
            setSessionCookie(tmp);

        //configure the name of the session id path param set by an init param
        tmp = _contextHandler.getInitParameter(SessionConfig.__SessionIdPathParameterNameProperty);
        if (tmp != null)
            setSessionIdPathParameterName(tmp);

        //configure checkRemoteSessionEncoding set by an init param
        tmp = _contextHandler.getInitParameter(SessionConfig.__CheckRemoteSessionEncodingProperty);
        if (tmp != null)
            setCheckingRemoteSessionIdEncoding(Boolean.parseBoolean(tmp));

        //configure the domain of the session cookie set by an init param
        tmp = _contextHandler.getInitParameter(SessionConfig.__SessionDomainProperty);
        if (tmp != null)
            setSessionDomain(tmp);

        //configure the path of the session cookie set by an init param
        tmp = _contextHandler.getInitParameter(SessionConfig.__SessionPathProperty);
        if (tmp != null)
            setSessionPath(tmp);

        //configure the max age of the session cookie set by an init param
        tmp = _contextHandler.getInitParameter(SessionConfig.__MaxAgeProperty);
        if (tmp != null)
            setMaxCookieAge(Integer.parseInt(tmp.trim()));
    }

    @Override
    protected void doStart() throws Exception
    {
        _contextHandler = ContextHandler.getCurrentContext().getContextHandler();
        super.doStart();
        configureCookies();
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
            if (_sessionManager.isUsingUriParameters())
                return Set.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
            return Set.of(SessionTrackingMode.COOKIE);
        }

        if (_sessionManager.isUsingUriParameters())
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
        _sessionManager.setUsingUriParameters(sessionTrackingModes != null && sessionTrackingModes.contains(SessionTrackingMode.URL));
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
    public boolean isPartitioned()
    {
        return _sessionManager.isPartitioned();
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
    public boolean isUsingUriParameters()
    {
        return _sessionManager.isUsingUriParameters();
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
    public void setPartitioned(boolean value)
    {
        _sessionManager.setPartitioned(value);
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
    public void setUsingUriParameters(boolean value)
    {
        _sessionManager.setUsingUriParameters(value);
    }

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        ContextHandler.CoreContextRequest coreRequest = baseRequest.getHttpChannel().getCoreRequest();
        SessionManager oldSessionManager = null;
        ManagedSession oldSession = null;
        AbstractSessionManager.RequestedSession oldRequestedSession = null;

        ManagedSession currentSession = null;
        AbstractSessionManager.RequestedSession currentRequestedSession = null;

        try
        {
            switch (baseRequest.getDispatcherType())
            {
                case REQUEST:
                {
                    //add a HttpStream.wrapper to commit and complete sessions created/loaded during this request
                    _sessionManager.addSessionStreamWrapper(coreRequest);
                    // find and set the session if one exists, along with an appropriate session manager
                    currentRequestedSession = _sessionManager.resolveRequestedSessionId(coreRequest);
                    coreRequest.setSessionManager(_sessionManager);
                    coreRequest.setRequestedSession(currentRequestedSession);
                    if (currentRequestedSession != null)
                    {
                        coreRequest.setManagedSession(currentRequestedSession.session());
                        currentSession = currentRequestedSession.session();
                    }
                    break;
                }
                case ASYNC:
                case ERROR:
                case FORWARD:
                case INCLUDE:
                {
                    oldSessionManager = coreRequest.getSessionManager();
                    oldSession = coreRequest.getManagedSession();
                    oldRequestedSession = coreRequest.getRequestedSession();

                    //We have been cross context dispatched. Could be from the same type of context, or a different
                    //type of context. If from the same type of context, the request is preserved and mutated during the
                    //dispatch, so a HttpStream.Wrapper would already have been added to it. If from a different type
                    //of context, we cannot share the HttpStream.Wrapper  and so we need to add a new one.
                    if (oldSessionManager == null)
                        _sessionManager.addSessionStreamWrapper(coreRequest);

                    //check if we have changed contexts during the dispatch
                    if (oldSessionManager != _sessionManager)
                    {
                        //find any existing session for this context that has already been accessed
                        currentSession = coreRequest.getManagedSession(_sessionManager);

                        if (currentSession == null)
                        {
                            //session for this context has not been already loaded, try getting it
                            coreRequest.setManagedSession(null);
                            currentRequestedSession = _sessionManager.resolveRequestedSessionId(coreRequest);
                            currentSession = currentRequestedSession.session();
                        }
                        else
                            currentRequestedSession = new AbstractSessionManager.RequestedSession(currentSession, currentSession.getId(), false /*TODO!!!*/);

                        coreRequest.setManagedSession(currentSession);
                        coreRequest.setRequestedSession(currentRequestedSession);
                        coreRequest.setSessionManager(_sessionManager);
                    }
                    break;
                }
                default:
                    break;
            }

            //first time the request has entered this context, or we have changed context
            if ((currentSession != null) && (oldSessionManager != _sessionManager))
            {
                HttpCookie cookie = _sessionManager.access(currentRequestedSession.session(), coreRequest.getConnectionMetaData().isSecure());

                // Handle changed ID or max-age refresh, but only if this is not a redispatched request
                if ((cookie != null) &&
                    (request.getDispatcherType() == DispatcherType.ASYNC ||
                        request.getDispatcherType() == DispatcherType.REQUEST))
                    baseRequest.getResponse().replaceCookie(cookie);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("sessionHandler={} session={}", this, currentSession);

            nextScope(target, baseRequest, request, response);
        }
        finally
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Leaving scope {} dispatch={}, async={}, session={}, oldsession={}, oldsessionhandler={}",
                    this, baseRequest.getDispatcherType(), baseRequest.isAsyncStarted(), baseRequest.getSession(false),
                    oldSession, oldSessionManager);

            // revert the session handler to the previous, unless it was null, in which case remember it as
            // the first session handler encountered.
            if (oldSessionManager != null && oldSessionManager != _sessionManager)
            {
                coreRequest.setSessionManager(oldSessionManager);
                coreRequest.setManagedSession(oldSession);
                coreRequest.setRequestedSession(oldRequestedSession);
            }
        }
    }

    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        nextHandle(target, baseRequest, request, response);
    }

    /**
     * CookieConfig
     *
     * Implementation of the jakarta.servlet.SessionCookieConfig.
     * SameSite configuration can be achieved by using setComment.
     * Partitioned configuration can be achieved by using setComment.
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

            if (!StringUtil.isEmpty(comment))
            {
                HttpCookie.SameSite sameSite = Response.HttpCookieFacade.getSameSiteFromComment(comment);
                if (sameSite != null)
                    _sessionManager.setSameSite(sameSite);

                boolean partitioned = Response.HttpCookieFacade.isPartitionedInComment(comment);
                if (partitioned)
                    _sessionManager.setPartitioned(partitioned);

                _sessionManager.setSessionComment(Response.HttpCookieFacade.getCommentWithoutAttributes(comment));
            }
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

        @Override
        public String toString()
        {
            return String.format("%s@%x[name=%s,domain=%s,path=%s,max-age=%d,secure=%b,http-only=%b,same-site=%s,comment=%s]",
                this.getClass().getName(), this.hashCode(), _sessionManager.getSessionCookie(), _sessionManager.getSessionDomain(), _sessionManager.getSessionPath(),
                _sessionManager.getMaxCookieAge(), _sessionManager.isSecureCookies(), _sessionManager.isHttpOnly(), _sessionManager.getSameSite(), _sessionManager.getSessionComment());
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
        protected void addSessionStreamWrapper(org.eclipse.jetty.server.Request request)
        {
            final ContextHandler.CoreContextRequest coreRequest = (ContextHandler.CoreContextRequest)request;
            coreRequest.addHttpStreamWrapper(s -> new SessionStreamWrapper(s, coreRequest));
        }

        @Override
        public ManagedSession getManagedSession(org.eclipse.jetty.server.Request request)
        {
            return org.eclipse.jetty.server.Request.get(request, ContextHandler.CoreContextRequest.class, ContextHandler.CoreContextRequest::getHttpChannel)
                .getCoreRequest().getManagedSession();
        }

        @Override
        public Session.API newSessionAPIWrapper(ManagedSession session)
        {
            return new ServletSessionApi(session);
        }

        @Override
        protected RequestedSession resolveRequestedSessionId(org.eclipse.jetty.server.Request request)
        {
            return super.resolveRequestedSessionId(request);
        }

        @Override
        public void onSessionAttributeUpdate(Session session, String name, Object old, Object value)
        {
            if (old != null)
                callUnboundBindingListener(session, name, old);
            if (value != null)
                callBoundBindingListener(session, name, value);

            if (!_sessionAttributeListeners.isEmpty())
            {
                HttpSessionBindingEvent event = new HttpSessionBindingEvent(session.getApi(), name, old == null ? value : old);

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
        public void onSessionCreated(Session session)
        {
            if (session == null)
                return;
            super.onSessionCreated(session);
            HttpSessionEvent event = new HttpSessionEvent(session.getApi());
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
        public void onSessionDestroyed(Session session)
        {
            if (session == null)
                return;

            super.onSessionDestroyed(session);

            //We annoint the calling thread with
            //the webapp's classloader because the calling thread may
            //come from the scavenger, rather than a request thread
            Runnable r = () ->
            {
                HttpSessionEvent event = new HttpSessionEvent(session.getApi());
                for (ListIterator<HttpSessionListener> i = TypeUtil.listIteratorAtEnd(_sessionListeners); i.hasPrevious();)
                {
                    i.previous().sessionDestroyed(event);
                }
            };
            _contextHandler.getCoreContextHandler().getContext().run(r);
        }

        @Override
        public void onSessionIdChanged(Session session, String oldId)
        {
            //inform the listeners
            super.onSessionIdChanged(session, oldId);
            if (!_sessionIdListeners.isEmpty())
            {
                HttpSessionEvent event = new HttpSessionEvent(session.getApi());
                for (HttpSessionIdListener l : _sessionIdListeners)
                {
                    l.sessionIdChanged(event, oldId);
                }
            }
        }

        protected void callUnboundBindingListener(Session session, String name, Object value)
        {
            if (value instanceof HttpSessionBindingListener)
                ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(session.getApi(), name));
        }

        protected void callBoundBindingListener(Session session, String name, Object value)
        {
            if (value instanceof HttpSessionBindingListener)
                ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(session.getApi(), name));
        }

        @Override
        public void onSessionActivation(Session session)
        {
            for (String name : session.getAttributeNameSet())
            {
                Object value = session.getAttribute(name);
                if (value instanceof HttpSessionActivationListener listener)
                {
                    HttpSessionEvent event = new HttpSessionEvent(session.getApi());
                    listener.sessionDidActivate(event);
                }
            }
        }

        @Override
        public void onSessionPassivation(Session session)
        {
            for (String name : session.getAttributeNameSet())
            {
                Object value = session.getAttribute(name);
                if (value instanceof HttpSessionActivationListener listener)
                {
                    HttpSessionEvent event = new HttpSessionEvent(session.getApi());
                    listener.sessionWillPassivate(event);
                }
            }
        }
    }

    public class ServletSessionApi implements HttpSession, Session.API
    {
        public static Function<Boolean, Session> getOrCreateSession(ServletRequest servletRequest)
        {
            return createSession ->
            {
                if (servletRequest instanceof HttpServletRequest request)
                {
                    HttpSession session = request.getSession(createSession);
                    if (session instanceof SessionHandler.ServletSessionApi sessionApi)
                        return sessionApi.getSession();
                }
                return null;
            };
        }

        private final ManagedSession _session;

        private ServletSessionApi(ManagedSession session)
        {
            _session = session;
        }

        @Override
        public ManagedSession getSession()
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
            return Collections.enumeration(_session.getAttributeNameSet());
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
            return _session.getAttributeNameSet().toArray(new String[0]);
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

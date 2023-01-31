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

package org.eclipse.jetty.ee10.servlet;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpCookie.SameSite;
import org.eclipse.jetty.http.Syntax;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionHandler extends AbstractSessionManager implements Handler.Nested
{    
    static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);
    
    public static final EnumSet<SessionTrackingMode> DEFAULT_SESSION_TRACKING_MODES =
        EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
    
    final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<>();
    final List<HttpSessionListener> _sessionListeners = new CopyOnWriteArrayList<>();
    final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<>();
    private final SessionCookieConfig _cookieConfig = new CookieConfig();

    private ServletContextHandler.ServletScopedContext _servletContextHandlerContext;

    private Server _server;
    private Handler _handler;

    @Override
    public void setServer(Server server)
    {
        _server = server;
        Handler handler = getHandler();
        if (handler != null)
            handler.setServer(server);
    }

    @Override
    public InvocationType getInvocationType()
    {
        // Session operations may be blocking
        return InvocationType.BLOCKING;
    }

    @Override
    public Handler getHandler()
    {
        return _handler;
    }

    @Override
    public void setHandler(Handler handler)
    {
        _handler = Nested.updateHandler(this, handler);
    }

    @Override
    public Server getServer()
    {
        return _server;
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
            return getSessionComment();
        }

        @Override
        public String getDomain()
        {
            return getSessionDomain();
        }

        @Override
        public int getMaxAge()
        {
            return getMaxCookieAge();
        }

        @Override
        public void setAttribute(String name, String value)
        {
            checkState();
            String lcase = name.toLowerCase(Locale.ENGLISH);

            switch (lcase)
            {
                case "name" -> setName(value);
                case "max-age" -> setMaxAge(value == null ? -1 : Integer.parseInt(value));
                case "comment" -> setComment(value);
                case "domain" -> setDomain(value);
                case "httponly" -> setHttpOnly(Boolean.valueOf(value));
                case "secure" -> setSecure(Boolean.valueOf(value));
                case "path" -> setPath(value);
                default -> setSessionAttribute(name, value);
            }
        }

        @Override
        public String getAttribute(String name)
        {
            String lcase = name.toLowerCase(Locale.ENGLISH);
            return switch (lcase)
            {
                case "name" -> getName();
                case "max-age" -> Integer.toString(getMaxAge());
                case "comment" -> getComment();
                case "domain" -> getDomain();
                case "httponly" -> String.valueOf(isHttpOnly());
                case "secure" -> String.valueOf(isSecure());
                case "path" -> getPath();
                default -> getSessionAttribute(name);
            };
        }

        /**
         * According to the SessionCookieConfig javadoc, the attributes must also include
         * all values set by explicit setters.
         * @see SessionCookieConfig
         */
        @Override
        public Map<String, String> getAttributes()
        {
            Map<String, String> specials = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            specials.put("name", getAttribute("name"));
            specials.put("max-age", getAttribute("max-age"));
            specials.put("comment", getAttribute("comment"));
            specials.put("domain", getAttribute("domain"));
            specials.put("httponly", getAttribute("httponly"));
            specials.put("secure", getAttribute("secure"));
            specials.put("path", getAttribute("path"));
            specials.putAll(getSessionAttributes());
            return Collections.unmodifiableMap(specials);
        }

        @Override
        public String getName()
        {
            return getSessionCookie();
        }

        @Override
        public String getPath()
        {
            return getSessionPath();
        }

        @Override
        public boolean isHttpOnly()
        {
            return SessionHandler.this.isHttpOnly();
        }

        @Override
        public boolean isSecure()
        {
            return SessionHandler.this.isSecureCookies();
        }

        @Override
        public void setComment(String comment)
        {
            checkState();
            SessionHandler.this.setSessionComment(comment);
        }

        @Override
        public void setDomain(String domain)
        {
            checkState();
            SessionHandler.this.setSessionDomain(domain);
        }

        @Override
        public void setHttpOnly(boolean httpOnly)
        {
            checkState();
            SessionHandler.this.setHttpOnly(httpOnly);
        }

        @Override
        public void setMaxAge(int maxAge)
        {
            checkState();
            SessionHandler.this.setMaxCookieAge(maxAge);
        }

        @Override
        public void setName(String name)
        {
            checkState();
            if ("".equals(name))
                throw new IllegalArgumentException("Blank cookie name");
            if (name != null)
                Syntax.requireValidRFC2616Token(name, "Bad Session cookie name");
            SessionHandler.this.setSessionCookie(name);
        }

        @Override
        public void setPath(String path)
        {
            checkState();
            SessionHandler.this.setSessionPath(path);
        }

        @Override
        public void setSecure(boolean secure)
        {
            checkState();
            SessionHandler.this.setSecureCookies(secure);
        }

        private void checkState()
        {
            if (_servletContextHandlerContext != null && _servletContextHandlerContext.getServletContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
        }
    }

    public static class ServletAPISession implements HttpSession, Session.APISession
    {
        public static ServletAPISession wrapSession(Session session)
        {
            return new ServletAPISession(session);
        }
        
        public static Session getSession(HttpSession httpSession)
        {
            if (httpSession instanceof ServletAPISession apiSession)
                return apiSession.getCoreSession();
            return null;
        }
        
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
            return ServletContextHandler.getServletContext((ContextHandler.ScopedContext)_session.getSessionManager().getContext());
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
            final Iterator<String> itor = _session.getNames().iterator();
            return new Enumeration<>()
            {

                @Override
                public boolean hasMoreElements()
                {
                    return itor.hasNext();
                }

                @Override
                public String nextElement()
                {
                    return itor.next();
                }
            };
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
    }

    public SessionHandler()
    {
        setSessionTrackingModes(DEFAULT_SESSION_TRACKING_MODES);
    }

    @Override
    public Session getSession(Request request)
    {
        ServletApiRequest apiRequest = Request.get(request, ServletContextRequest.class, ServletContextRequest::getServletApiRequest);
        return apiRequest == null ? null : apiRequest.getCoreSession();
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
    
    @Override
    public void doStart() throws Exception
    {
        super.doStart();
        if (!(getContext() instanceof ServletContextHandler.ServletScopedContext))
            throw new IllegalStateException("!ServlerContextHandler.Context");
        _servletContextHandlerContext = (ServletContextHandler.ServletScopedContext)getContext();
        configureCookies();
    }

    /**
     * Set up cookie configuration based on init params
     */
    protected void configureCookies()
    {
    }

    public Session.APISession newSessionAPIWrapper(Session session)
    {
        return ServletAPISession.wrapSession(session);
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
        {
            l.sessionCreated(event);
        }
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
        getSessionContext().run(() ->
        {
            HttpSessionEvent event = new HttpSessionEvent(session.getAPISession());
            for (int i = _sessionListeners.size() - 1; i >= 0; i--)
            {
                _sessionListeners.get(i).sessionDestroyed(event);
            }
        });
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
        if (isUsingCookies())
        {
            if (isUsingURLs())
                return Set.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
            return Set.of(SessionTrackingMode.COOKIE);
        }

        if (isUsingURLs())
            return Set.of(SessionTrackingMode.URL);

        return Collections.emptySet();
    }
    
    @Override
    public HttpCookie.SameSite getSameSite()
    {
        String sameSite = getSessionAttribute("SameSite");
        if (sameSite == null)
            return null;
        return SameSite.valueOf(sameSite.toUpperCase(Locale.ENGLISH));
    }
    
    /**
     * Set Session cookie sameSite mode.
     * In ee10 this is set as a generic session cookie attribute.
     *
     * @param sameSite The sameSite setting for Session cookies (or null for no sameSite setting)
     */
    @Override
    public void setSameSite(HttpCookie.SameSite sameSite)
    {
        setSessionAttribute("SameSite", sameSite.getAttributeValue());
    }
    
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        if (sessionTrackingModes != null &&
            sessionTrackingModes.size() > 1 &&
            sessionTrackingModes.contains(SessionTrackingMode.SSL))
        {
            throw new IllegalArgumentException("sessionTrackingModes specifies a combination of SessionTrackingMode.SSL with a session tracking mode other than SessionTrackingMode.SSL");
        }
        setUsingCookies(sessionTrackingModes != null && sessionTrackingModes.contains(SessionTrackingMode.COOKIE));
        setUsingURLs(sessionTrackingModes != null && sessionTrackingModes.contains(SessionTrackingMode.URL));
    }

    @Override
    public boolean process(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        ServletApiRequest servletApiRequest =
            (servletContextRequest == null ? null : servletContextRequest.getServletApiRequest());
        if (servletApiRequest == null)
            throw new IllegalStateException("Request is not a valid ServletContextRequest");

        addSessionStreamWrapper(request);

        // find and set the session if one exists
        RequestedSession requestedSession = resolveRequestedSessionId(request);

        servletApiRequest.setCoreSession(requestedSession.session());
        servletApiRequest.setSessionManager(this);
        servletApiRequest.setRequestedSessionId(requestedSession.sessionId());
        servletApiRequest.setRequestedSessionIdFromCookie(requestedSession.sessionIdFromCookie());

        HttpCookie cookie = access(requestedSession.session(), request.getConnectionMetaData().isSecure());

        // Handle changed ID or max-age refresh, but only if this is not a redispatched request
        if (cookie != null)
        {
            ServletContextResponse servletContextResponse = servletContextRequest.getResponse();
            Response.replaceCookie(servletContextResponse, cookie);
        }

        return next.process(request, response, callback);
    }
}

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

package org.eclipse.jetty.ee10.servlet;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
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
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.session.AbstractSessionManager;
import org.eclipse.jetty.session.ManagedSession;
import org.eclipse.jetty.session.SessionConfig;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;

public class SessionHandler extends AbstractSessionManager implements Handler.Singleton
{
    public static final EnumSet<SessionTrackingMode> DEFAULT_SESSION_TRACKING_MODES =
        EnumSet.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
    
    final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<>();
    final List<HttpSessionListener> _sessionListeners = new CopyOnWriteArrayList<>();
    final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<>();
    private final SessionCookieConfig _cookieConfig = new CookieConfig();

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
        _handler = Handler.Singleton.updateHandler(this, handler);
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    /**
     * CookieConfig
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
                case "httponly" -> setHttpOnly(Boolean.parseBoolean(value));
                case "secure" -> setSecure(Boolean.parseBoolean(value));
                case "path" -> setPath(value);
                default -> setSessionCookieAttribute(name, value);
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
                default -> getSessionCookieAttribute(name);
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
            specials.putAll(getSessionCookieAttributes());
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

        @Override
        public String toString()
        {
              return String.format("%s@%x[name=%s,domain=%s,path=%s,max-age=%d,secure=%b,http-only=%b,comment=%s,attributes=%s]",
                this.getClass().getName(), this.hashCode(), getName(), getDomain(), getPath(),
               getMaxAge(), isSecure(), isHttpOnly(),  getComment(), getSessionCookieAttributes().toString());
        }

        private void checkState()
        {
            //It is allowable to call the CookieConfig.setXX methods after the SessionHandler has started,
            //but before the context has fully started. Ie it is allowable for ServletContextListeners
            //to call these methods in contextInitialized().
            ServletContextHandler handler = ServletContextHandler.getCurrentServletContextHandler();
            if (handler != null && handler.isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");

        }
    }

    public static class ServletSessionApi implements HttpSession, Session.API
    {
        public static ServletSessionApi wrapSession(ManagedSession session)
        {
            return new ServletSessionApi(session);
        }
        
        public static ManagedSession getSession(HttpSession httpSession)
        {
            if (httpSession instanceof ServletSessionApi apiSession)
                return apiSession.getSession();
            return null;
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
            return ServletContextHandler.getServletContext(_session.getSessionManager().getContext());
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
            final Iterator<String> itor = _session.getAttributeNameSet().iterator();
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
        installBean(_cookieConfig);
        installBean(_sessionListeners);
        installBean(_sessionIdListeners);
        installBean(_sessionAttributeListeners);
    }

    @Override
    public ManagedSession getManagedSession(Request request)
    {
        ServletContextRequest servletContextRequest = Request.asInContext(request, ServletContextRequest.class);
        if (servletContextRequest != null)
            return servletContextRequest.getManagedSession();

        NonServletSessionRequest nonServletSessionRequest = Request.asInContext(request, NonServletSessionRequest.class);
        if (nonServletSessionRequest != null)
            return nonServletSessionRequest.getManagedSession();

        if (request.getSession(false) instanceof ManagedSession managedSession)
            return managedSession;
        return null;
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
        configureCookies();
    }

    /**
     * Set up cookie configuration based on init params
     */
    protected void configureCookies()
    {
        ServletContextHandler contextHandler = ServletContextHandler.getCurrentServletContextHandler();
        if (contextHandler == null)
            return;

        //configure the name of the session cookie set by an init param
        String tmp = contextHandler.getInitParameter(SessionConfig.__SessionCookieProperty);
        if (tmp != null)
            setSessionCookie(tmp);

        //configure the name of the session id path param set by an init param
        tmp = contextHandler.getInitParameter(SessionConfig.__SessionIdPathParameterNameProperty);
        if (tmp != null)
            setSessionIdPathParameterName(tmp);

        //configure checkRemoteSessionEncoding set by an init param
        tmp = contextHandler.getInitParameter(SessionConfig.__CheckRemoteSessionEncodingProperty);
        if (tmp != null)
            setCheckingRemoteSessionIdEncoding(Boolean.parseBoolean(tmp));

        //configure the domain of the session cookie set by an init param
        tmp = contextHandler.getInitParameter(SessionConfig.__SessionDomainProperty);
        if (tmp != null)
            setSessionDomain(tmp);

        //configure the path of the session cookie set by an init param
        tmp = contextHandler.getInitParameter(SessionConfig.__SessionPathProperty);
        if (tmp != null)
            setSessionPath(tmp);

        //configure the max age of the session cookie set by an init param
        tmp = contextHandler.getInitParameter(SessionConfig.__MaxAgeProperty);
        if (tmp != null)
            setMaxCookieAge(Integer.parseInt(tmp.trim()));
    }

    public Session.API newSessionAPIWrapper(ManagedSession session)
    {
        return ServletSessionApi.wrapSession(session);
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
    public void onSessionDestroyed(Session session)
    {
        if (session == null)
            return;
        super.onSessionDestroyed(session);
        //We annoint the calling thread with
        //the webapp's classloader because the calling thread may
        //come from the scavenger, rather than a request thread
        getSessionContext().run(() ->
        {
            HttpSessionEvent event = new HttpSessionEvent(session.getApi());
            for (ListIterator<HttpSessionListener> i = TypeUtil.listIteratorAtEnd(_sessionListeners); i.hasPrevious();)
            {
                i.previous().sessionDestroyed(event);
            }
        });
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
            if (isUsingUriParameters())
                return Set.of(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);
            return Set.of(SessionTrackingMode.COOKIE);
        }

        if (isUsingUriParameters())
            return Set.of(SessionTrackingMode.URL);

        return Collections.emptySet();
    }
    
    @Override
    public HttpCookie.SameSite getSameSite()
    {
        String sameSite = getSessionCookieAttribute("SameSite");
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
        setSessionCookieAttribute("SameSite", sameSite.getAttributeValue());
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
        setUsingUriParameters(sessionTrackingModes != null && sessionTrackingModes.contains(SessionTrackingMode.URL));
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        Handler next = getHandler();
        if (next == null)
            return false;

        addSessionStreamWrapper(request);

        // find and set the session if one exists
        RequestedSession requestedSession = resolveRequestedSessionId(request);

        ServletContextRequest servletContextRequest = Request.as(request, ServletContextRequest.class);
        if (servletContextRequest == null)
            request = new NonServletSessionRequest(request, response, requestedSession);
        else
            servletContextRequest.setRequestedSession(requestedSession);

        // Handle changed ID or max-age refresh, but only if this is not a redispatched request
        HttpCookie cookie = access(requestedSession.session(), request.getConnectionMetaData().isSecure());
        if (cookie != null)
            Response.putCookie(response, cookie);

        return next.handle(request, response, callback);
    }

    private class NonServletSessionRequest extends Request.Wrapper
    {
        private final Response _response;
        private RequestedSession _session;

        public NonServletSessionRequest(Request request, Response response, RequestedSession requestedSession)
        {
            super(request);
            _response = response;
            _session = requestedSession;
        }

        @Override
        public Session getSession(boolean create)
        {
            ManagedSession session = _session.session();

            if (session != null || !create)
                return session;

            newSession(getWrapped(), _session.sessionId(), ms ->
                _session = new RequestedSession(ms, _session.sessionId(), true));

            session = _session.session();
            if (session == null)
                throw new IllegalStateException("Create session failed");

            HttpCookie cookie = getSessionCookie(session, isSecure());
            if (cookie != null)
                Response.replaceCookie(_response, cookie);
            return session;
        }

        ManagedSession getManagedSession()
        {
            return _session.session();
        }
    }
}

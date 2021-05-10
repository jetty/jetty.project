//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionContext;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.Syntax;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Math.round;

/**
 * SessionHandler.
 */
@ManagedObject
public class SessionHandler extends ScopedHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(SessionHandler.class);

    public static final EnumSet<SessionTrackingMode> DEFAULT_TRACKING = EnumSet.of(SessionTrackingMode.COOKIE,
        SessionTrackingMode.URL);

    /**
     * Session cookie name.
     * Defaults to <code>JSESSIONID</code>, but can be set with the
     * <code>org.eclipse.jetty.servlet.SessionCookie</code> context init parameter.
     */
    public static final String __SessionCookieProperty = "org.eclipse.jetty.servlet.SessionCookie";
    public static final String __DefaultSessionCookie = "JSESSIONID";

    /**
     * Session id path parameter name.
     * Defaults to <code>jsessionid</code>, but can be set with the
     * <code>org.eclipse.jetty.servlet.SessionIdPathParameterName</code> context init parameter.
     * If context init param is "none", or setSessionIdPathParameterName is called with null or "none",
     * no URL rewriting will be done.
     */
    public static final String __SessionIdPathParameterNameProperty = "org.eclipse.jetty.servlet.SessionIdPathParameterName";
    public static final String __DefaultSessionIdPathParameterName = "jsessionid";
    public static final String __CheckRemoteSessionEncoding = "org.eclipse.jetty.servlet.CheckingRemoteSessionIdEncoding";

    /**
     * Session Domain.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the domain for session cookies. If it is not set, then
     * no domain is specified for the session cookie.
     */
    public static final String __SessionDomainProperty = "org.eclipse.jetty.servlet.SessionDomain";
    public static final String __DefaultSessionDomain = null;

    /**
     * Session Path.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the path for the session cookie.  If it is not set, then
     * the context path is used as the path for the cookie.
     */
    public static final String __SessionPathProperty = "org.eclipse.jetty.servlet.SessionPath";

    /**
     * Session Max Age.
     * If this property is set as a ServletContext InitParam, then it is
     * used as the max age for the session cookie.  If it is not set, then
     * a max age of -1 is used.
     */
    public static final String __MaxAgeProperty = "org.eclipse.jetty.servlet.MaxAge";

    public static final Set<SessionTrackingMode> DEFAULT_SESSION_TRACKING_MODES =
        Collections.unmodifiableSet(
            new HashSet<>(
                Arrays.asList(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));

    @SuppressWarnings("unchecked")
    public static final Class<? extends EventListener>[] SESSION_LISTENER_TYPES =
        new Class[]
            {
                HttpSessionAttributeListener.class,
                HttpSessionIdListener.class,
                HttpSessionListener.class
            };

    @Deprecated(since = "Servlet API 2.1")
    static final HttpSessionContext __nullSessionContext = new HttpSessionContext()
    {
        @Override
        @Deprecated(since = "Servlet API 2.1")
        public HttpSession getSession(String sessionId)
        {
            return null;
        }

        @Override
        @Deprecated(since = "Servlet API 2.1")
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Enumeration getIds()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }
    };

    /**
     * Setting of max inactive interval for new sessions
     * -1 means no timeout
     */
    protected int _dftMaxIdleSecs = -1;
    protected boolean _httpOnly = false;
    protected SessionIdManager _sessionIdManager;
    protected boolean _secureCookies = false;
    protected boolean _secureRequestOnly = true;

    protected final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<>();
    protected final List<HttpSessionListener> _sessionListeners = new CopyOnWriteArrayList<>();
    protected final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<>();

    protected ClassLoader _loader;
    protected ContextHandler.Context _context;
    protected SessionContext _sessionContext;
    protected String _sessionCookie = __DefaultSessionCookie;
    protected String _sessionIdPathParameterName = __DefaultSessionIdPathParameterName;
    protected String _sessionIdPathParameterNamePrefix = ";" + _sessionIdPathParameterName + "=";
    protected String _sessionDomain;
    protected String _sessionPath;
    protected int _maxCookieAge = -1;
    protected int _refreshCookieAge;
    protected boolean _checkingRemoteSessionIdEncoding;
    protected String _sessionComment;
    protected SessionCache _sessionCache;
    protected final SampleStatistic _sessionTimeStats = new SampleStatistic();
    protected final CounterStatistic _sessionsCreatedStats = new CounterStatistic();
    public Set<SessionTrackingMode> _sessionTrackingModes;

    protected boolean _usingURLs;
    protected boolean _usingCookies = true;

    protected Set<String> _candidateSessionIdsForExpiry = ConcurrentHashMap.newKeySet();

    protected Scheduler _scheduler;
    protected boolean _ownScheduler = false;

    /**
     * Constructor.
     */
    public SessionHandler()
    {
        setSessionTrackingModes(DEFAULT_SESSION_TRACKING_MODES);
    }

    @ManagedAttribute("path of the session cookie, or null for default")
    public String getSessionPath()
    {
        return _sessionPath;
    }

    @ManagedAttribute("if greater the zero, the time in seconds a session cookie will last for")
    public int getMaxCookieAge()
    {
        return _maxCookieAge;
    }

    /**
     * Called by the {@link SessionHandler} when a session is first accessed by a request.
     *
     * Updates the last access time for the session and generates a fresh cookie if necessary.
     *
     * @param session the session object
     * @param secure whether the request is secure or not
     * @return the session cookie. If not null, this cookie should be set on the response to either migrate
     * the session or to refresh a session cookie that may expire.
     * @see #complete(HttpSession)
     */
    public HttpCookie access(HttpSession session, boolean secure)
    {
        long now = System.currentTimeMillis();

        Session s = ((SessionIf)session).getSession();

        if (s.access(now))
        {
            // Do we need to refresh the cookie?
            if (isUsingCookies() &&
                (s.isIdChanged() ||
                    (getSessionCookieConfig().getMaxAge() > 0 && getRefreshCookieAge() > 0 &&
                        ((now - s.getCookieSetTime()) / 1000 > getRefreshCookieAge()))))
            {
                HttpCookie cookie = getSessionCookie(session, _context == null ? "/" : (_context.getContextPath()), secure);
                s.cookieSet();
                s.setIdChanged(false);
                return cookie;
            }
        }
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

    /**
     * Call the session lifecycle listeners in
     * the reverse order they were added.
     *
     * @param session the session on which to call the lifecycle listeners
     */
    protected void callSessionDestroyedListeners(Session session)
    {
        if (session == null)
            return;

        if (_sessionListeners != null)
        {
            //We annoint the calling thread with
            //the webapp's classloader because the calling thread may
            //come from the scavenger, rather than a request thread
            Runnable r = new Runnable()
            {
                @Override
                public void run()
                {
                    HttpSessionEvent event = new HttpSessionEvent(session);
                    for (int i = _sessionListeners.size() - 1; i >= 0; i--)
                    {
                        _sessionListeners.get(i).sessionDestroyed(event);
                    }
                }
            };
            _sessionContext.run(r);
        }
    }

    /**
     * Call the session lifecycle listeners in the order
     * they were added.
     *
     * @param session the session on which to call the lifecycle listeners
     */
    protected void callSessionCreatedListeners(Session session)
    {
        if (session == null)
            return;

        if (_sessionListeners != null)
        {
            HttpSessionEvent event = new HttpSessionEvent(session);
            for (HttpSessionListener  l : _sessionListeners)
            {
                l.sessionCreated(event);
            }
        }
    }

    protected void callSessionIdListeners(Session session, String oldId)
    {
        //inform the listeners
        if (!_sessionIdListeners.isEmpty())
        {
            HttpSessionEvent event = new HttpSessionEvent(session);
            for (HttpSessionIdListener l : _sessionIdListeners)
            {
                l.sessionIdChanged(event, oldId);
            }
        }
    }

    /**
     * Called when a request is finally leaving a session.
     *
     * @param session the session object
     */
    public void complete(HttpSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Complete called with session {}", session);

        if (session == null)
            return;

        Session s = ((SessionIf)session).getSession();
        try
        {
            _sessionCache.release(s.getId(), s);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to release Session {}", s, e);
        }
    }

    /**
     * Called when a response is about to be committed.
     * We might take this opportunity to persist the session
     * so that any subsequent requests to other servers
     * will see the modifications.
     */
    public void commit(HttpSession session)
    {
        if (session == null)
            return;

        Session s = ((SessionIf)session).getSession();
        try
        {
            _sessionCache.commit(s);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to commit Session {}", s, e);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        //check if session management is set up, if not set up HashSessions
        final Server server = getServer();

        _context = ContextHandler.getCurrentContext();
        _loader = Thread.currentThread().getContextClassLoader();

        // Use a coarser lock to serialize concurrent start of many contexts.
        synchronized (server)
        {
            //Get a SessionDataStore and a SessionDataStore, falling back to in-memory sessions only
            if (_sessionCache == null)
            {
                SessionCacheFactory ssFactory = server.getBean(SessionCacheFactory.class);
                setSessionCache(ssFactory != null ? ssFactory.getSessionCache(this) : new DefaultSessionCache(this));
                SessionDataStore sds = null;
                SessionDataStoreFactory sdsFactory = server.getBean(SessionDataStoreFactory.class);
                if (sdsFactory != null)
                    sds = sdsFactory.getSessionDataStore(this);
                else
                    sds = new NullSessionDataStore();

                _sessionCache.setSessionDataStore(sds);
            }

            if (_sessionIdManager == null)
            {
                _sessionIdManager = server.getSessionIdManager();
                if (_sessionIdManager == null)
                {
                    //create a default SessionIdManager and set it as the shared
                    //SessionIdManager for the Server, being careful NOT to use
                    //the webapp context's classloader, otherwise if the context
                    //is stopped, the classloader is leaked.
                    ClassLoader serverLoader = server.getClass().getClassLoader();
                    try
                    {
                        Thread.currentThread().setContextClassLoader(serverLoader);
                        _sessionIdManager = new DefaultSessionIdManager(server);
                        server.setSessionIdManager(_sessionIdManager);
                        server.manage(_sessionIdManager);
                        _sessionIdManager.start();
                    }
                    finally
                    {
                        Thread.currentThread().setContextClassLoader(_loader);
                    }
                }

                // server session id is never managed by this manager
                addBean(_sessionIdManager, false);
            }

            _scheduler = server.getBean(Scheduler.class);
            if (_scheduler == null)
            {
                _scheduler = new ScheduledExecutorScheduler(String.format("Session-Scheduler-%x", hashCode()), false);
                _ownScheduler = true;
                _scheduler.start();
            }
        }

        // Look for a session cookie name
        if (_context != null)
        {
            String tmp = _context.getInitParameter(__SessionCookieProperty);
            if (tmp != null)
                _sessionCookie = tmp;

            tmp = _context.getInitParameter(__SessionIdPathParameterNameProperty);
            if (tmp != null)
                setSessionIdPathParameterName(tmp);

            // set up the max session cookie age if it isn't already
            if (_maxCookieAge == -1)
            {
                tmp = _context.getInitParameter(__MaxAgeProperty);
                if (tmp != null)
                    _maxCookieAge = Integer.parseInt(tmp.trim());
            }

            // set up the session domain if it isn't already
            if (_sessionDomain == null)
                _sessionDomain = _context.getInitParameter(__SessionDomainProperty);

            // set up the sessionPath if it isn't already
            if (_sessionPath == null)
                _sessionPath = _context.getInitParameter(__SessionPathProperty);

            tmp = _context.getInitParameter(__CheckRemoteSessionEncoding);
            if (tmp != null)
                _checkingRemoteSessionIdEncoding = Boolean.parseBoolean(tmp);
        }

        _sessionContext = new SessionContext(_sessionIdManager.getWorkerName(), _context);
        _sessionCache.initialize(_sessionContext);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        // Destroy sessions before destroying servlets/filters see JETTY-1266
        shutdownSessions();
        _sessionCache.stop();
        if (_ownScheduler && _scheduler != null)
            _scheduler.stop();
        _scheduler = null;
        super.doStop();
        _loader = null;
    }

    /**
     * @return true if session cookies should be HTTP-only (Microsoft extension)
     * @see org.eclipse.jetty.http.HttpCookie#isHttpOnly()
     */
    @ManagedAttribute("true if cookies use the http only flag")
    public boolean getHttpOnly()
    {
        return _httpOnly;
    }

    /**
     * @return The sameSite setting for session cookies or null for no setting
     * @see HttpCookie#getSameSite()
     */
    @ManagedAttribute("SameSite setting for session cookies")
    public HttpCookie.SameSite getSameSite()
    {
        return HttpCookie.getSameSiteFromComment(_sessionComment);
    }

    /**
     * Returns the <code>HttpSession</code> with the given session id
     *
     * @param extendedId the session id
     * @return the <code>HttpSession</code> with the corresponding id or null if no session with the given id exists
     */
    protected HttpSession getHttpSession(String extendedId)
    {
        String id = getSessionIdManager().getId(extendedId);
        Session session = getSession(id);

        if (session != null && !session.getExtendedId().equals(extendedId))
            session.setIdChanged(true);
        return session;
    }

    /**
     * Gets the cross context session id manager
     *
     * @return the session id manager
     */
    @ManagedAttribute("Session ID Manager")
    public SessionIdManager getSessionIdManager()
    {
        return _sessionIdManager;
    }

    /**
     * @return the max period of inactivity, after which the session is invalidated, in seconds.
     * @see #setMaxInactiveInterval(int)
     */
    @ManagedAttribute("default maximum time a session may be idle for (in s)")
    public int getMaxInactiveInterval()
    {
        return _dftMaxIdleSecs;
    }

    @ManagedAttribute("time before a session cookie is re-set (in s)")
    public int getRefreshCookieAge()
    {
        return _refreshCookieAge;
    }

    /**
     * @return same as SessionCookieConfig.getSecure(). If true, session
     * cookies are ALWAYS marked as secure. If false, a session cookie is
     * ONLY marked as secure if _secureRequestOnly == true and it is an HTTPS request.
     */
    @ManagedAttribute("if true, secure cookie flag is set on session cookies")
    public boolean getSecureCookies()
    {
        return _secureCookies;
    }

    /**
     * @return true if session cookie is to be marked as secure only on HTTPS requests
     */
    public boolean isSecureRequestOnly()
    {
        return _secureRequestOnly;
    }

    /**
     * HTTPS request. Can be overridden by setting SessionCookieConfig.setSecure(true),
     * in which case the session cookie will be marked as secure on both HTTPS and HTTP.
     *
     * @param secureRequestOnly true to set Session Cookie Config as secure
     */
    public void setSecureRequestOnly(boolean secureRequestOnly)
    {
        _secureRequestOnly = secureRequestOnly;
    }

    @ManagedAttribute("the set session cookie")
    public String getSessionCookie()
    {
        return _sessionCookie;
    }

    /**
     * A session cookie is marked as secure IFF any of the following conditions are true:
     * <ol>
     * <li>SessionCookieConfig.setSecure == true</li>
     * <li>SessionCookieConfig.setSecure == false &amp;&amp; _secureRequestOnly==true &amp;&amp; request is HTTPS</li>
     * </ol>
     * According to SessionCookieConfig javadoc, case 1 can be used when:
     * "... even though the request that initiated the session came over HTTP,
     * is to support a topology where the web container is front-ended by an
     * SSL offloading load balancer. In this case, the traffic between the client
     * and the load balancer will be over HTTPS, whereas the traffic between the
     * load balancer and the web container will be over HTTP."
     * <p>
     * For case 2, you can use _secureRequestOnly to determine if you want the
     * Servlet Spec 3.0  default behavior when SessionCookieConfig.setSecure==false,
     * which is:
     * <cite>
     * "they shall be marked as secure only if the request that initiated the
     * corresponding session was also secure"
     * </cite>
     * <p>
     * The default for _secureRequestOnly is true, which gives the above behavior. If
     * you set it to false, then a session cookie is NEVER marked as secure, even if
     * the initiating request was secure.
     *
     * @param session the session to which the cookie should refer.
     * @param contextPath the context to which the cookie should be linked.
     * The client will only send the cookie value when requesting resources under this path.
     * @param requestIsSecure whether the client is accessing the server over a secure protocol (i.e. HTTPS).
     * @return if this <code>SessionManager</code> uses cookies, then this method will return a new
     * {@link Cookie cookie object} that should be set on the client in order to link future HTTP requests
     * with the <code>session</code>. If cookies are not in use, this method returns <code>null</code>.
     */
    public HttpCookie getSessionCookie(HttpSession session, String contextPath, boolean requestIsSecure)
    {
        if (isUsingCookies())
        {
            SessionCookieConfig cookieConfig = getSessionCookieConfig();
            String sessionPath = (cookieConfig.getPath() == null) ? contextPath : cookieConfig.getPath();
            sessionPath = (StringUtil.isEmpty(sessionPath)) ? "/" : sessionPath;
            String id = getExtendedId(session);
            HttpCookie cookie = null;

            cookie = new HttpCookie(
                getSessionCookieName(_cookieConfig),
                id,
                cookieConfig.getDomain(),
                sessionPath,
                cookieConfig.getMaxAge(),
                cookieConfig.isHttpOnly(),
                cookieConfig.isSecure() || (isSecureRequestOnly() && requestIsSecure),
                HttpCookie.getCommentWithoutAttributes(cookieConfig.getComment()),
                0,
                HttpCookie.getSameSiteFromComment(cookieConfig.getComment()));

            return cookie;
        }
        return null;
    }

    @ManagedAttribute("domain of the session cookie, or null for the default")
    public String getSessionDomain()
    {
        return _sessionDomain;
    }

    @ManagedAttribute("number of sessions created by this node")
    public int getSessionsCreated()
    {
        return (int)_sessionsCreatedStats.getCurrent();
    }

    /**
     * @return the URL path parameter name for session id URL rewriting, by default "jsessionid".
     * @see #setSessionIdPathParameterName(String)
     */
    @ManagedAttribute("name of use for URL session tracking")
    public String getSessionIdPathParameterName()
    {
        return _sessionIdPathParameterName;
    }

    /**
     * @return a formatted version of {@link #getSessionIdPathParameterName()}, by default
     * ";" + sessionIdParameterName + "=", for easier lookup in URL strings.
     * @see #getSessionIdPathParameterName()
     */
    public String getSessionIdPathParameterNamePrefix()
    {
        return _sessionIdPathParameterNamePrefix;
    }

    /**
     * @return whether the session management is handled via cookies.
     */
    public boolean isUsingCookies()
    {
        return _usingCookies;
    }

    /**
     * @param session the session to test for validity
     * @return whether the given session is valid, that is, it has not been invalidated.
     */
    public boolean isValid(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.isValid();
    }

    /**
     * @param session the session object
     * @return the unique id of the session within the cluster (without a node id extension)
     * @see #getExtendedId(HttpSession)
     */
    public String getId(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.getId();
    }

    /**
     * @param session the session object
     * @return the unique id of the session within the cluster, extended with an optional node id.
     * @see #getId(HttpSession)
     */
    public String getExtendedId(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.getExtendedId();
    }

    /**
     * Creates a new <code>HttpSession</code>.
     *
     * @param request the HttpServletRequest containing the requested session id
     * @return the new <code>HttpSession</code>
     */
    public HttpSession newHttpSession(HttpServletRequest request)
    {
        long created = System.currentTimeMillis();
        String id = _sessionIdManager.newSessionId(request, created);
        Session session = _sessionCache.newSession(request, id, created, (_dftMaxIdleSecs > 0 ? _dftMaxIdleSecs * 1000L : -1));
        session.setExtendedId(_sessionIdManager.getExtendedId(id, request));
        session.getSessionData().setLastNode(_sessionIdManager.getWorkerName());

        try
        {
            _sessionCache.add(id, session);
            Request baseRequest = Request.getBaseRequest(request);
            baseRequest.setSession(session);
            baseRequest.enterSession(session);
            _sessionsCreatedStats.increment();

            if (request != null && request.isSecure())
                session.setAttribute(Session.SESSION_CREATED_SECURE, Boolean.TRUE);

            callSessionCreatedListeners(session);

            return session;
        }
        catch (Exception e)
        {
            LOG.warn("Unable to add Session {}", id, e);
            return null;
        }
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

    /**
     * Reset statistics values
     */
    @ManagedOperation(value = "reset statistics", impact = "ACTION")
    public void statsReset()
    {
        _sessionsCreatedStats.reset();
        _sessionTimeStats.reset();
    }

    /**
     * Set if Session cookies should use HTTP Only
     *
     * @param httpOnly True if cookies should be HttpOnly.
     * @see HttpCookie
     */
    public void setHttpOnly(boolean httpOnly)
    {
        _httpOnly = httpOnly;
    }

    /**
     * Set Session cookie sameSite mode.
     * Currently this is encoded in the session comment until sameSite is supported by {@link SessionCookieConfig}
     *
     * @param sameSite The sameSite setting for Session cookies (or null for no sameSite setting)
     */
    public void setSameSite(HttpCookie.SameSite sameSite)
    {
        // Encode in comment whilst not supported by SessionConfig, so that it can be set/saved in
        // web.xml and quickstart.
        // Always pass false for httpOnly as it has it's own setter.
        _sessionComment = HttpCookie.getCommentWithAttributes(_sessionComment, false, sameSite);
    }

    /**
     * @param metaManager The metaManager used for cross context session management.
     */
    public void setSessionIdManager(SessionIdManager metaManager)
    {
        updateBean(_sessionIdManager, metaManager);
        _sessionIdManager = metaManager;
    }

    /**
     * Sets the max period of inactivity, after which the session is invalidated, in seconds.
     *
     * @param seconds the max inactivity period, in seconds.
     * @see #getMaxInactiveInterval()
     */
    public void setMaxInactiveInterval(int seconds)
    {
        _dftMaxIdleSecs = seconds;
        if (LOG.isDebugEnabled())
        {
            if (_dftMaxIdleSecs <= 0)
                LOG.debug("Sessions created by this manager are immortal (default maxInactiveInterval={})", _dftMaxIdleSecs);
            else
                LOG.debug("SessionManager default maxInactiveInterval={}", _dftMaxIdleSecs);
        }
    }

    public void setRefreshCookieAge(int ageInSeconds)
    {
        _refreshCookieAge = ageInSeconds;
    }

    public void setSessionCookie(String cookieName)
    {
        _sessionCookie = cookieName;
    }

    /**
     * Sets the session id URL path parameter name.
     *
     * @param param the URL path parameter name for session id URL rewriting (null or "none" for no rewriting).
     * @see #getSessionIdPathParameterName()
     * @see #getSessionIdPathParameterNamePrefix()
     */
    public void setSessionIdPathParameterName(String param)
    {
        _sessionIdPathParameterName = (param == null || "none".equals(param)) ? null : param;
        _sessionIdPathParameterNamePrefix = (param == null || "none".equals(param))
            ? null : (";" + _sessionIdPathParameterName + "=");
    }

    /**
     * @param usingCookies The usingCookies to set.
     */
    public void setUsingCookies(boolean usingCookies)
    {
        _usingCookies = usingCookies;
    }

    /**
     * Get a known existing session
     *
     * @param id The session ID stripped of any worker name.
     * @return A Session or null if none exists.
     */
    public Session getSession(String id)
    {
        try
        {
            Session session = _sessionCache.get(id);
            if (session != null)
            {
                //If the session we got back has expired
                if (session.isExpiredAt(System.currentTimeMillis()))
                {
                    //Expire the session
                    try
                    {
                        session.invalidate();
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Invalidating session {} found to be expired when requested", id, e);
                    }

                    return null;
                }

                session.setExtendedId(_sessionIdManager.getExtendedId(id, null));
            }
            return session;
        }
        catch (UnreadableSessionDataException e)
        {
            LOG.warn("Error loading session {}", id, e);
            try
            {
                //tell id mgr to remove session from all other contexts
                getSessionIdManager().invalidateAll(id);
            }
            catch (Exception x)
            {
                LOG.warn("Error cross-context invalidating unreadable session {}", id, x);
            }
            return null;
        }
        catch (Exception other)
        {
            LOG.warn("Unable to get Session", other);
            return null;
        }
    }

    /**
     * Prepare sessions for session manager shutdown
     *
     * @throws Exception if unable to shutdown sesssions
     */
    protected void shutdownSessions() throws Exception
    {
        _sessionCache.shutdown();
    }

    /**
     * @return the session store
     */
    public SessionCache getSessionCache()
    {
        return _sessionCache;
    }

    /**
     * @param cache the session store to use
     */
    public void setSessionCache(SessionCache cache)
    {
        updateBean(_sessionCache, cache);
        _sessionCache = cache;
    }

    /**
     * Remove session from manager
     *
     * @param id The session to remove
     * @param invalidate True if {@link HttpSessionListener#sessionDestroyed(HttpSessionEvent)} and
     * {@link SessionIdManager#expireAll(String)} should be called.
     * @return if the session was removed
     */
    public Session removeSession(String id, boolean invalidate)
    {
        try
        {
            //Remove the Session object from the session store and any backing data store
            Session session = _sessionCache.delete(id);
            if (session != null)
            {
                if (invalidate)
                {
                    session.beginInvalidate();

                    if (_sessionListeners != null)
                    {
                        HttpSessionEvent event = new HttpSessionEvent(session);
                        for (int i = _sessionListeners.size() - 1; i >= 0; i--)
                        {
                            _sessionListeners.get(i).sessionDestroyed(event);
                        }
                    }
                }
            }
            //TODO if session object is not known to this node, how to get rid of it if no other
            //node knows about it?

            return session;
        }
        catch (Exception e)
        {
            LOG.warn("Unable to remove Session", e);
            return null;
        }
    }

    /**
     * @return maximum amount of time session remained valid
     */
    @ManagedAttribute("maximum amount of time sessions have remained active (in s)")
    public long getSessionTimeMax()
    {
        return _sessionTimeStats.getMax();
    }

    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        return DEFAULT_SESSION_TRACKING_MODES;
    }

    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        return Collections.unmodifiableSet(_sessionTrackingModes);
    }

    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        if (sessionTrackingModes != null &&
            sessionTrackingModes.size() > 1 &&
            sessionTrackingModes.contains(SessionTrackingMode.SSL))
        {
            throw new IllegalArgumentException("sessionTrackingModes specifies a combination of SessionTrackingMode.SSL with a session tracking mode other than SessionTrackingMode.SSL");
        }
        _sessionTrackingModes = new HashSet<>(sessionTrackingModes);
        _usingCookies = _sessionTrackingModes.contains(SessionTrackingMode.COOKIE);
        _usingURLs = _sessionTrackingModes.contains(SessionTrackingMode.URL);
    }

    /**
     * @return whether the session management is handled via URLs.
     */
    public boolean isUsingURLs()
    {
        return _usingURLs;
    }

    public SessionCookieConfig getSessionCookieConfig()
    {
        return _cookieConfig;
    }

    private SessionCookieConfig _cookieConfig =
        new CookieConfig();

    /**
     * @return total amount of time all sessions remained valid
     */
    @ManagedAttribute("total time sessions have remained valid")
    public long getSessionTimeTotal()
    {
        return _sessionTimeStats.getTotal();
    }

    /**
     * @return mean amount of time session remained valid
     */
    @ManagedAttribute("mean time sessions remain valid (in s)")
    public double getSessionTimeMean()
    {
        return _sessionTimeStats.getMean();
    }

    /**
     * @return standard deviation of amount of time session remained valid
     */
    @ManagedAttribute("standard deviation a session remained valid (in s)")
    public double getSessionTimeStdDev()
    {
        return _sessionTimeStats.getStdDev();
    }

    /**
     * @return True if absolute URLs are check for remoteness before being session encoded.
     */
    @ManagedAttribute("check remote session id encoding")
    public boolean isCheckingRemoteSessionIdEncoding()
    {
        return _checkingRemoteSessionIdEncoding;
    }

    /**
     * @param remote True if absolute URLs are check for remoteness before being session encoded.
     */
    public void setCheckingRemoteSessionIdEncoding(boolean remote)
    {
        _checkingRemoteSessionIdEncoding = remote;
    }

    /**
     * Change the existing session id.
     *
     * @param oldId the old session id
     * @param oldExtendedId the session id including worker suffix
     * @param newId the new session id
     * @param newExtendedId the new session id including worker suffix
     */
    public void renewSessionId(String oldId, String oldExtendedId, String newId, String newExtendedId)
    {
        Session session = null;
        try
        {
            //the use count for the session will be incremented in renewSessionId
            session = _sessionCache.renewSessionId(oldId, newId, oldExtendedId, newExtendedId); //swap the id over
            if (session == null)
            {
                //session doesn't exist on this context
                return;
            }

            //inform the listeners
            callSessionIdListeners(session, oldId);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to renew Session Id {}:{} -> {}:{}", oldId, oldExtendedId, newId, newExtendedId, e);
        }
        finally
        {
            if (session != null)
            {
                try
                {
                    _sessionCache.release(newId, session);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to release {}", newId, e);
                }
            }
        }
    }

    /**
     * Record length of time session has been active. Called when the
     * session is about to be invalidated.
     *
     * @param session the session whose time to record
     */
    protected void recordSessionTime(Session session)
    {
        _sessionTimeStats.record(round((System.currentTimeMillis() - session.getSessionData().getCreated()) / 1000.0));
    }

    /**
     * Called by SessionIdManager to remove a session that has been invalidated,
     * either by this context or another context. Also called by
     * SessionIdManager when a session has expired in either this context or
     * another context.
     *
     * @param id the session id to invalidate
     */
    public void invalidate(String id)
    {

        if (StringUtil.isBlank(id))
            return;

        try
        {
            // Remove the Session object from the session cache and any backing
            // data store
            Session session = _sessionCache.delete(id);
            if (session != null)
            {
                //start invalidating if it is not already begun, and call the listeners
                try
                {
                    if (session.beginInvalidate())
                    {
                        try
                        {
                            callSessionDestroyedListeners(session);
                        }
                        catch (Exception e)
                        {
                            LOG.warn("Error during Session destroy listener", e);
                        }
                        //call the attribute removed listeners and finally mark it as invalid
                        session.finishInvalidate();
                    }
                }
                catch (IllegalStateException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} already invalid", session, e);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to delete Session {}", id, e);
        }
    }

    /**
     * Called periodically by the HouseKeeper to handle the list of
     * sessions that have expired since the last call to scavenge.
     */
    public void scavenge()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("{} scavenging sessions", this);
        //Get a snapshot of the candidates as they are now. Others that
        //arrive during this processing will be dealt with on 
        //subsequent call to scavenge
        String[] ss = _candidateSessionIdsForExpiry.toArray(new String[0]);
        Set<String> candidates = new HashSet<>(Arrays.asList(ss));
        _candidateSessionIdsForExpiry.removeAll(candidates);
        if (LOG.isDebugEnabled())
            LOG.debug("{} scavenging session ids {}", this, candidates);
        try
        {
            candidates = _sessionCache.checkExpiration(candidates);
            for (String id : candidates)
            {
                try
                {
                    getSessionIdManager().expireAll(id);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to expire Session {}", id, e);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("Failed to check expiration on {}",
                candidates.stream().map(Objects::toString).collect(Collectors.joining(", ", "[", "]")),
                e);
        }
    }

    /**
     * Each session has a timer that is configured to go off
     * when either the session has not been accessed for a
     * configurable amount of time, or the session itself
     * has passed its expiry.
     *
     * If it has passed its expiry, then we will mark it for
     * scavenging by next run of the HouseKeeper; if it has
     * been idle longer than the configured eviction period,
     * we evict from the cache.
     *
     * If none of the above are true, then the System timer
     * is inconsistent and the caller of this method will
     * need to reset the timer.
     *
     * @param session the session
     * @param now the time at which to check for expiry
     */
    public void sessionInactivityTimerExpired(Session session, long now)
    {
        if (session == null)
            return;

        //check if the session is:
        //1. valid
        //2. expired
        //3. idle
        try (AutoLock lock = session.lock())
        {
            if (session.getRequests() > 0)
                return; //session can't expire or be idle if there is a request in it

            if (LOG.isDebugEnabled())
                LOG.debug("Inspecting session {}, valid={}", session.getId(), session.isValid());

            if (!session.isValid())
                return; //do nothing, session is no longer valid

            if (session.isExpiredAt(now))
            {
                //instead of expiring the session directly here, accumulate a list of 
                //session ids that need to be expired. This is an efficiency measure: as
                //the expiration involves the SessionDataStore doing a delete, it is 
                //most efficient if it can be done as a bulk operation to eg reduce
                //roundtrips to the persistent store. Only do this if the HouseKeeper that
                //does the scavenging is configured to actually scavenge
                if (_sessionIdManager.getSessionHouseKeeper() != null &&
                    _sessionIdManager.getSessionHouseKeeper().getIntervalSec() > 0)
                {
                    _candidateSessionIdsForExpiry.add(session.getId());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} is candidate for expiry", session.getId());
                }
            }
            else
            {
                //possibly evict the session
                _sessionCache.checkInactiveSession(session);
            }
        }
    }

    /**
     * Check if id is in use by this context
     *
     * @param id identity of session to check
     * @return <code>true</code> if this manager knows about this id
     * @throws Exception if any error occurred
     */
    public boolean isIdInUse(String id) throws Exception
    {
        //Ask the session store
        return _sessionCache.exists(id);
    }

    public Scheduler getScheduler()
    {
        return _scheduler;
    }

    /**
     * SessionIf
     *
     * Interface that any session wrapper should implement so that
     * SessionManager may access the Jetty session implementation.
     */
    public interface SessionIf extends HttpSession
    {
        public Session getSession();
    }

    public static String getSessionCookieName(SessionCookieConfig config)
    {
        if (config == null || config.getName() == null)
            return __DefaultSessionCookie;
        return config.getName();
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
            return _sessionComment;
        }

        @Override
        public String getDomain()
        {
            return _sessionDomain;
        }

        @Override
        public int getMaxAge()
        {
            return _maxCookieAge;
        }

        @Override
        public String getName()
        {
            return _sessionCookie;
        }

        @Override
        public String getPath()
        {
            return _sessionPath;
        }

        @Override
        public boolean isHttpOnly()
        {
            return _httpOnly;
        }

        @Override
        public boolean isSecure()
        {
            return _secureCookies;
        }

        @Override
        public void setComment(String comment)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionComment = comment;
        }

        @Override
        public void setDomain(String domain)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionDomain = domain;
        }

        @Override
        public void setHttpOnly(boolean httpOnly)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _httpOnly = httpOnly;
        }

        @Override
        public void setMaxAge(int maxAge)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _maxCookieAge = maxAge;
        }

        @Override
        public void setName(String name)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            if ("".equals(name))
                throw new IllegalArgumentException("Blank cookie name");
            if (name != null)
                Syntax.requireValidRFC2616Token(name, "Bad Session cookie name");
            _sessionCookie = name;
        }

        @Override
        public void setPath(String path)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionPath = path;
        }

        @Override
        public void setSecure(boolean secure)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _secureCookies = secure;
        }
    }

    public void doSessionAttributeListeners(Session session, String name, Object old, Object value)
    {
        if (!_sessionAttributeListeners.isEmpty())
        {
            HttpSessionBindingEvent event = new HttpSessionBindingEvent(session, name, old == null ? value : old);

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

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        SessionHandler oldSessionHandler = null;
        HttpSession oldSession = null;
        HttpSession existingSession = null;

        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Entering scope {}, dispatch={} asyncstarted={}", this, baseRequest.getDispatcherType(), baseRequest
                    .isAsyncStarted());

            switch (baseRequest.getDispatcherType())
            {
                case REQUEST:
                {
                    //there are no previous sessionhandlers or sessions for dispatch=REQUEST
                    //look for a session for this context
                    baseRequest.setSession(null);
                    checkRequestedSessionId(baseRequest, request);
                    existingSession = baseRequest.getSession(false);
                    baseRequest.setSessionHandler(this);
                    baseRequest.setSession(existingSession); //can be null
                    break;
                }
                case ASYNC:
                case ERROR:
                case FORWARD:
                case INCLUDE:
                {
                    //remember previous sessionhandler and session
                    oldSessionHandler = baseRequest.getSessionHandler();
                    oldSession = baseRequest.getSession(false);

                    if (oldSessionHandler != this)
                    {
                        //find any existing session for this request that has already been accessed
                        existingSession = baseRequest.getSession(this);
                        if (existingSession == null)
                        {
                            //session for this context has not been visited previously,
                            //try getting it
                            baseRequest.setSession(null);
                            checkRequestedSessionId(baseRequest, request);
                            existingSession = baseRequest.getSession(false);
                        }

                        baseRequest.setSession(existingSession);
                        baseRequest.setSessionHandler(this);
                    }
                    break;
                }
                default:
                    break;
            }

            if ((existingSession != null) && (oldSessionHandler != this))
            {
                HttpCookie cookie = access(existingSession, request.isSecure());
                // Handle changed ID or max-age refresh, but only if this is not a redispatched request
                if ((cookie != null) &&
                    (request.getDispatcherType() == DispatcherType.ASYNC ||
                        request.getDispatcherType() == DispatcherType.REQUEST))
                    baseRequest.getResponse().replaceCookie(cookie);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("sessionHandler={} session={}", this, existingSession);

            if (_nextScope != null)
                _nextScope.doScope(target, baseRequest, request, response);
            else if (_outerScope != null)
                _outerScope.doHandle(target, baseRequest, request, response);
            else
                doHandle(target, baseRequest, request, response);
        }
        finally
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Leaving scope {} dispatch={}, async={}, session={}, oldsession={}, oldsessionhandler={}",
                    this, baseRequest.getDispatcherType(), baseRequest.isAsyncStarted(), baseRequest.getSession(false),
                    oldSession, oldSessionHandler);

            // revert the session handler to the previous, unless it was null, in which case remember it as
            // the first session handler encountered.
            if (oldSessionHandler != null && oldSessionHandler != this)
            {
                baseRequest.setSessionHandler(oldSessionHandler);
                baseRequest.setSession(oldSession);
            }
        }
    }

    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        nextHandle(target, baseRequest, request, response);
    }

    /**
     * Look for a requested session ID in cookies and URI parameters
     *
     * @param baseRequest the request to check
     * @param request the request to check
     */
    protected void checkRequestedSessionId(Request baseRequest, HttpServletRequest request)
    {
        String requestedSessionId = request.getRequestedSessionId();

        if (requestedSessionId != null)
        {
            HttpSession session = getHttpSession(requestedSessionId);

            if (session != null && isValid(session))
            {
                baseRequest.enterSession(session); //enter session for first time
                baseRequest.setSession(session);
            }
            return;
        }
        else if (!DispatcherType.REQUEST.equals(baseRequest.getDispatcherType()))
            return;

        boolean requestedSessionIdFromCookie = false;
        HttpSession session = null;

        //first try getting id from a cookie
        if (isUsingCookies())
        {
            Cookie[] cookies = request.getCookies();
            if (cookies != null && cookies.length > 0)
            {
                final String sessionCookie = getSessionCookieName(getSessionCookieConfig());
                for (Cookie cookie : cookies)
                {
                    if (sessionCookie.equalsIgnoreCase(cookie.getName()))
                    {
                        String id = cookie.getValue();
                        requestedSessionIdFromCookie = true;
                        if (LOG.isDebugEnabled())
                            LOG.debug("Got Session ID {} from cookie {}", id, sessionCookie);

                        if (session == null)
                        {
                            //we currently do not have a session selected, use this one if it is valid
                            HttpSession s = getHttpSession(id);
                            if (s != null && isValid(s))
                            {
                                //associate it with the request so its reference count is decremented as the
                                //request exits
                                requestedSessionId = id;
                                session = s;
                                baseRequest.enterSession(session);
                                baseRequest.setSession(session);

                                if (LOG.isDebugEnabled())
                                    LOG.debug("Selected session {}", session);
                            }
                            else
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("No session found for session cookie id {}", id);

                                //if we don't have a valid session id yet, just choose the current id
                                if (requestedSessionId == null)
                                    requestedSessionId = id;
                            }
                        }
                        else
                        {
                            //we currently have a valid session selected. We will throw an error
                            //if there is a _different_ valid session id cookie. Duplicate ids, or
                            //invalid session ids are ignored
                            if (!session.getId().equals(getSessionIdManager().getId(id)))
                            {
                                //load the session to see if it is valid or not
                                HttpSession s = getHttpSession(id);
                                if (s != null && isValid(s))
                                {
                                    //associate it with the request so its reference count is decremented as the
                                    //request exits
                                    baseRequest.enterSession(s);
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("Multiple different valid session ids: {}, {}", requestedSessionId, id);
                                    throw new BadMessageException("Duplicate valid session cookies: " + requestedSessionId + " ," + id);
                                }
                            }
                            else
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Duplicate valid session cookie id: {}", id);
                            }
                        }
                    }
                }
            }
        }

        //try getting id from a url
        if (isUsingURLs() && (requestedSessionId == null))
        {
            String uri = request.getRequestURI();
            String prefix = getSessionIdPathParameterNamePrefix();
            if (prefix != null)
            {
                int s = uri.indexOf(prefix);
                if (s >= 0)
                {
                    s += prefix.length();
                    int i = s;
                    while (i < uri.length())
                    {
                        char c = uri.charAt(i);
                        if (c == ';' || c == '#' || c == '?' || c == '/')
                            break;
                        i++;
                    }

                    requestedSessionId = uri.substring(s, i);
                    requestedSessionIdFromCookie = false;

                    if (LOG.isDebugEnabled())
                        LOG.debug("Got Session ID {} from URL", requestedSessionId);

                    session = getHttpSession(requestedSessionId);
                    if (session != null && isValid(session))
                    {
                        baseRequest.enterSession(session); //request enters this session for first time
                        baseRequest.setSession(session);  //associate the session with the request
                    }
                }
            }
        }

        baseRequest.setRequestedSessionId(requestedSessionId);
        baseRequest.setRequestedSessionIdFromCookie(requestedSessionId != null && requestedSessionIdFromCookie);
    }

    @Override
    public String toString()
    {
        return String.format("%s%d==dftMaxIdleSec=%d", this.getClass().getName(), this.hashCode(), _dftMaxIdleSecs);
    }
}

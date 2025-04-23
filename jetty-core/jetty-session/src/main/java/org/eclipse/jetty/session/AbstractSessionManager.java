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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Syntax;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractSessionHandler
 * Class to implement most non-servlet-spec specific session behaviour.
 */
public abstract class AbstractSessionManager extends ContainerLifeCycle implements SessionManager, SessionConfig.Mutable
{
    static final Logger LOG = LoggerFactory.getLogger(AbstractSessionManager.class);
    private final Set<String> _candidateSessionIdsForExpiry = ConcurrentHashMap.newKeySet();
    private final SampleStatistic _sessionTimeStats = new SampleStatistic();
    private final CounterStatistic _sessionsCreatedStats = new CounterStatistic();
    /**
     * Setting of max inactive interval for new sessions
     * -1 means no timeout
     */
    private int _dftMaxIdleSecs = -1;
    private boolean _usingUriParameters;
    private boolean _usingCookies = true;
    private SessionIdManager _sessionIdManager;
    private ClassLoader _loader;
    private Context _context;
    private SessionContext _sessionContext;
    private SessionCache _sessionCache;
    private Scheduler _scheduler;
    private boolean _ownScheduler = false;
    private String _sessionCookie;
    private String _sessionIdPathParameterName;
    private String _sessionIdPathParameterNamePrefix;
    private final Map<String, String> _sessionCookieAttributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Map<String, String> _sessionCookieSecureAttributes;
    private boolean _secureRequestOnly = true;
    private int _refreshCookieAge;
    private boolean _checkingRemoteSessionIdEncoding;
    private List<Session.LifeCycleListener> _sessionLifeCycleListeners = Collections.emptyList();

    public AbstractSessionManager()
    {
    }
    
    /**
     * Called when a session is first accessed by request processing.
     * Updates the last access time for the session and generates a fresh cookie if necessary.
     *
     * @param session the session object
     * @param secure whether the request is secure or not
     * @return the session cookie. If not null, this cookie should be set on the response to either migrate
     * the session or to refresh a session cookie that may expire.
     * @see #complete(ManagedSession)
     */
    public HttpCookie access(ManagedSession session, boolean secure)
    {
        if (session == null)
            return null;

        long now = System.currentTimeMillis();

        if (session.access(now))
        {
            // Do we need to refresh the cookie?
            if (isUsingCookies() &&
                (session.isSetCookieNeeded() ||
                    (getMaxCookieAge() > 0 && getRefreshCookieAge() > 0 &&
                        ((now - session.getCookieSetTime()) / 1000 > getRefreshCookieAge()))))
            {
                return getSessionCookie(session, secure);
            }
        }
        return null;
    }

    /**
     * Calculate what the session timer setting should be based on:
     * the time remaining before the session expires
     * and any idle eviction time configured.
     * The timer value will be the lesser of the above.
     *
     * @param id the ID of the session
     * @param timeRemainingMs The time in milliseconds remaining before this session is considered Idle
     * @param maxInactiveMs The maximum time in milliseconds that a session may be idle.
     * @return the time remaining before expiry or inactivity timeout
     */
    @Override
    public long calculateInactivityTimeout(String id, long timeRemainingMs, long maxInactiveMs)
    {
        long time;

        int evictionPolicy = _sessionCache.getEvictionPolicy();
        if (maxInactiveMs <= 0)
        {
            // sessions are immortal, they never expire
            if (evictionPolicy < SessionCache.EVICT_ON_INACTIVITY)
            {
                //session not subject to timeouts
                time = -1;
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} is immortal && no inactivity eviction", id);
            }
            else
            {
                // sessions are immortal but can be evicted, timeout is the eviction timeout
                time = TimeUnit.SECONDS.toMillis(evictionPolicy);
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} is immortal; evict after {} sec inactivity", id, evictionPolicy);
            }
        }
        else
        {
            // sessions are not immortal
            if (evictionPolicy == SessionCache.NEVER_EVICT)
            {
                //timeout is the time remaining until its expiry
                time = (timeRemainingMs > 0 ? timeRemainingMs : 0);
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} no eviction", id);
            }
            else if (evictionPolicy == SessionCache.EVICT_ON_SESSION_EXIT)
            {
                // session will not remain in the cache, so no timeout
                time = -1;
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} evict on exit", id);
            }
            else
            {
                // want to evict on idle: timeout is lesser of the session's
                // expiration remaining and the eviction timeout
                time = (timeRemainingMs > 0 ? (Math.min(maxInactiveMs, TimeUnit.SECONDS.toMillis(evictionPolicy))) : 0);

                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} timer set to lesser of maxInactive={} and inactivityEvict={}", id,
                        maxInactiveMs, evictionPolicy);
            }
        }

        return time;
    }

    /**
     * Called when a response is about to be committed.
     * We might take this opportunity to persist the session
     * so that any subsequent requests to other servers
     * will see the modifications.
     */
    @Override
    public void commit(ManagedSession session)
    {
        if (session == null)
            return;

        try
        {
            _sessionCache.commit(session);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to commit Session {}", session, e);
        }
    }

    /**
     * Called when a request is finally leaving a session.
     *
     * @param session the session object
     */
    @Override
    public void complete(ManagedSession session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Complete called with session {}", session);

        if (session == null)
            return;
        try
        {
            _sessionCache.release(session);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to release Session {}", session, e);
        }
    }

    @Override
    public void doStart() throws Exception
    {
        //check if session management is set up, if not set up defaults
        final Server server = getServer();

        _context = ContextHandler.getCurrentContext(server);
        _loader = Thread.currentThread().getContextClassLoader();

        //default the session cookie name
        if (getSessionCookie() == null)
            setSessionCookie(__DefaultSessionCookie);

        //default the session id parameter name
        if (getSessionIdPathParameterName() == null)
            setSessionIdPathParameterName(__DefaultSessionIdPathParameterName);


        // ensure a session path is set
        String contextPath = _context == null ? "/" : _context.getContextPath();
        if (getSessionPath() == null)
            setSessionPath(contextPath);

        // Use a coarser lock to serialize concurrent start of many contexts.
        synchronized (server)
        {
            //Get a SessionDataStore and a SessionDataStore, falling back to in-memory sessions only
            if (_sessionCache == null)
            {
                SessionCacheFactory ssFactory = server.getBean(SessionCacheFactory.class);
                setSessionCache(ssFactory != null ? ssFactory.getSessionCache(this) : new DefaultSessionCache(this));
                SessionDataStore sds;
                SessionDataStoreFactory sdsFactory = server.getBean(SessionDataStoreFactory.class);
                if (sdsFactory != null)
                    sds = sdsFactory.getSessionDataStore(this);
                else
                    sds = new NullSessionDataStore();

                _sessionCache.setSessionDataStore(sds);
            }

            if (_sessionIdManager == null)
            {
                _sessionIdManager = server.getBean(SessionIdManager.class);
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
                        server.addBean(_sessionIdManager, true);
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

            _scheduler = server.getScheduler();
            if (_scheduler == null)
            {
                _scheduler = new ScheduledExecutorScheduler(String.format("Session-Scheduler-%x", hashCode()), false);
                _ownScheduler = true;
                _scheduler.start();
            }
        }

        _sessionContext = new SessionContext(this);
        _sessionCache.initialize(_sessionContext);

        secureRequestOnlyAttributes();
        super.doStart();

        if (_context != null)
        {
            _sessionLifeCycleListeners = _context.getAttributeNameSet().stream()
                .map(_context::getAttribute)
                .filter(Session.LifeCycleListener.class::isInstance)
                .map(Session.LifeCycleListener.class::cast)
                .toList();
            addBean(_sessionLifeCycleListeners);
        }
    }
    
    public org.eclipse.jetty.server.Context getContext()
    {
        return _context;
    }
    
    @Override
    public int getMaxCookieAge()
    {
        String mca = _sessionCookieAttributes.get(HttpCookie.MAX_AGE_ATTRIBUTE);
        return mca == null ? -1 : Integer.parseInt(mca);
    }

    @Override
    public void setMaxCookieAge(int maxCookieAge)
    {
        _sessionCookieAttributes.put(HttpCookie.MAX_AGE_ATTRIBUTE, Integer.toString(maxCookieAge));
        secureRequestOnlyAttributes();
    }

    /**
     * @return the max period of inactivity, after which the session is invalidated, in seconds.
     *         If less than or equal to zero, then the session is immortal
     * @see #setMaxInactiveInterval(int)
     */
    @Override
    public int getMaxInactiveInterval()
    {
        return _dftMaxIdleSecs;
    }
    
    /**
     * Sets the max period of inactivity, after which the session is invalidated, in seconds.
     *
     * @param seconds the max inactivity period, in seconds. If less than or equal to zero, then the session is immortal
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

    @Override
    public int getRefreshCookieAge()
    {
        return _refreshCookieAge;
    }
    
    @Override
    public void setRefreshCookieAge(int ageInSeconds)
    {
        _refreshCookieAge = ageInSeconds;
    }
    
    public abstract Server getServer();

    /**
     * Get a known existing session
     *
     * @param extendedId The session id, possibly including worker name suffix.
     * @return the Session matching the id or null if none exists
     */
    @Override
    public ManagedSession getManagedSession(String extendedId)
    {
        String id = getSessionIdManager().getId(extendedId);
        try
        {
            ManagedSession session = _sessionCache.get(id);
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
                        if (LOG.isDebugEnabled())
                            LOG.warn("Invalidating session {} found to be expired when requested", id, e);
                    }

                    return null;
                }

                session.setExtendedId(_sessionIdManager.getExtendedId(id, null));
            }

            if (session != null && !session.getExtendedId().equals(extendedId))
                session.onIdChanged();

            return session;
        }
        catch (UnreadableSessionDataException e)
        {
            LOG.warn("Error loading session {}", id, e);
            try
            {
                //tell id mgr to remove session from all contexts
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
     * @return the session cache
     */
    @Override
    public SessionCache getSessionCache()
    {
        return _sessionCache;
    }

    /**
     * Set up the SessionCache.
     *
     * @param cache the SessionCache to use
     */
    @Override
    public void setSessionCache(SessionCache cache)
    {
        updateBean(_sessionCache, cache);
        _sessionCache = cache;
    }
    
    @Override
    public String getSessionComment()
    {
        return _sessionCookieAttributes.get(HttpCookie.COMMENT_ATTRIBUTE);
    }

    @Override
    public void setSessionComment(String sessionComment)
    {
        _sessionCookieAttributes.put(HttpCookie.COMMENT_ATTRIBUTE, sessionComment);
        secureRequestOnlyAttributes();
    }

    @Override
    public HttpCookie.SameSite getSameSite()
    {
        return HttpCookie.SameSite.from(_sessionCookieAttributes.get(HttpCookie.SAME_SITE_ATTRIBUTE));
    }

    @Override
    public void setSameSite(HttpCookie.SameSite sessionSameSite)
    {
        _sessionCookieAttributes.put(HttpCookie.SAME_SITE_ATTRIBUTE, sessionSameSite.getAttributeValue());
        secureRequestOnlyAttributes();
    }

    public SessionContext getSessionContext()
    {
        return _sessionContext;
    }

    @Override
    public String getSessionCookie()
    {
        return _sessionCookie;
    }
    
    @Override
    public void setSessionCookie(String cookieName)
    {
        if (StringUtil.isBlank(cookieName))
            throw new IllegalArgumentException("Blank cookie name");
        Syntax.requireValidRFC2616Token(cookieName, "Bad Session cookie name");
        _sessionCookie = cookieName;
    }

    @Override
    public String getSessionDomain()
    {
        return _sessionCookieAttributes.get(HttpCookie.DOMAIN_ATTRIBUTE);
    }
    
    @Override
    public void setSessionDomain(String domain)
    {
        _sessionCookieAttributes.put(HttpCookie.DOMAIN_ATTRIBUTE, domain);
        secureRequestOnlyAttributes();
    }
    
    public void setSessionCookieAttribute(String name, String value)
    {
        _sessionCookieAttributes.put(name, value);
        secureRequestOnlyAttributes();
    }
    
    public String getSessionCookieAttribute(String name)
    {
        return _sessionCookieAttributes.get(name);
    }
    
    /**
     * @return all of the cookie config attributes EXCEPT for
     * those that have explicit setter/getters
     */
    public Map<String, String> getSessionCookieAttributes()
    {
        return Collections.unmodifiableMap(_sessionCookieAttributes);
    }
    
    @Override
    public SessionIdManager getSessionIdManager()
    {
        return _sessionIdManager;
    }

    /**
     * Set up the SessionIdManager.
     *
     * @param sessionIdManager The sessionIdManager used for cross context session management.
     */
    @Override
    public void setSessionIdManager(SessionIdManager sessionIdManager)
    {
        updateBean(_sessionIdManager, sessionIdManager);
        _sessionIdManager = sessionIdManager;
    }

    /**
     * @return the URL path parameter name for session id URL rewriting, by default "jsessionid".
     * @see #setSessionIdPathParameterName(String)
     */
    @Override
    public String getSessionIdPathParameterName()
    {
        return _sessionIdPathParameterName;
    }

    /**
     * Sets the session id URL path parameter name.
     *
     * @param param the URL path parameter name for session id URL rewriting (null or "none" for no rewriting).
     * @see #getSessionIdPathParameterName()
     * @see #getSessionIdPathParameterNamePrefix()
     */
    @Override
    public void setSessionIdPathParameterName(String param)
    {
        _sessionIdPathParameterName = (param == null || "none".equals(param)) ? null : param;
        _sessionIdPathParameterNamePrefix = (param == null || "none".equals(param))
            ? null : (";" + _sessionIdPathParameterName + "=");
    }

    /**
     * @return a formatted version of {@link #getSessionIdPathParameterName()}, by default
     * ";" + sessionIdParameterName + "=", for easier lookup in URL strings.
     * @see #getSessionIdPathParameterName()
     */
    @Override
    public String getSessionIdPathParameterNamePrefix()
    {
        return _sessionIdPathParameterNamePrefix;
    }

    @Override
    public String getSessionPath()
    {
        return _sessionCookieAttributes.get(HttpCookie.PATH_ATTRIBUTE);
    }

    @Override
    public void setSessionPath(String sessionPath)
    {
        _sessionCookieAttributes.put(HttpCookie.PATH_ATTRIBUTE, sessionPath);
        secureRequestOnlyAttributes();
    }
    
    /**
     * @return mean amount of time session remained valid
     */
    @ManagedAttribute("mean time sessions remain valid (in s)")
    @Override
    public double getSessionTimeMean()
    {
        return _sessionTimeStats.getMean();
    }
    
    /**
     * @return standard deviation of amount of time session remained valid
     */
    @ManagedAttribute("standard deviation a session remained valid (in s)")
    @Override
    public double getSessionTimeStdDev()
    {
        return _sessionTimeStats.getStdDev();
    }

    /**
     * @return total amount of time all sessions remained valid
     */
    @ManagedAttribute("total time sessions have remained valid")
    @Override
    public long getSessionTimeTotal()
    {
        return _sessionTimeStats.getTotal();
    }
    
    @ManagedAttribute("number of sessions created by this context")
    @Override
    public int getSessionsCreated()
    {
        return (int)_sessionsCreatedStats.getCurrent();
    }

    @Override
    public String encodeURI(Request request, String uri, boolean cookiesInUse)
    {
        HttpURI httpURI = null;
        if (isCheckingRemoteSessionIdEncoding() && URIUtil.hasScheme(uri))
        {
            httpURI = HttpURI.from(uri);
            String path = httpURI.getPath();
            path = (path == null ? "" : path);
            int port = httpURI.getPort();
            if (port < 0)
            {
                String scheme = httpURI.getScheme();
                port = URIUtil.getDefaultPortForScheme(scheme);
            }

            // Is it the same server?
            if (!Request.getServerName(request).equalsIgnoreCase(httpURI.getHost()))
                return uri;
            if (Request.getServerPort(request) != port)
                return uri;
            if (request.getContext() != null && !path.startsWith(request.getContext().getContextPath()))
                return uri;
        }

        String sessionURLPrefix = getSessionIdPathParameterNamePrefix();
        if (sessionURLPrefix == null)
            return uri;

        if (uri == null)
            return null;

        // should not encode if cookies in evidence
        if ((isUsingCookies() && cookiesInUse) || !isUsingUriParameters())
        {
            // strip session param from URI
            int prefix = uri.indexOf(sessionURLPrefix);
            if (prefix != -1)
            {
                int suffix = uri.indexOf("?", prefix);
                if (suffix < 0)
                    suffix = uri.indexOf("#", prefix);

                if (suffix <= prefix)
                    return uri.substring(0, prefix);
                return uri.substring(0, prefix) + uri.substring(suffix);
            }
            return uri;
        }

        // get session;
        Session session = request.getSession(false);

        // no session
        if (session == null || !session.isValid())
            return uri;

        String id = session.getExtendedId();

        // Already encoded
        int prefix = uri.indexOf(sessionURLPrefix);
        if (prefix != -1)
        {
            int suffix = uri.indexOf("?", prefix);
            if (suffix < 0)
                suffix = uri.indexOf("#", prefix);

            if (suffix <= prefix)
                return uri.substring(0, prefix + sessionURLPrefix.length()) + id;
            return uri.substring(0, prefix + sessionURLPrefix.length()) + id +
                uri.substring(suffix);
        }

        // edit the session
        int suffix = uri.indexOf('?');
        if (suffix < 0)
            suffix = uri.indexOf('#');

        if (suffix < 0)
        {
            if (URIUtil.isRelative(uri))
            {
                return uri + sessionURLPrefix + id;
            }
            else
            {
                if (httpURI == null)
                    httpURI = HttpURI.from(uri);

                return uri +
                    ((HttpScheme.HTTPS.is(httpURI.getScheme()) || HttpScheme.HTTP.is(httpURI.getScheme())) && httpURI.getPath() == null ? "/" : "") + //if no path, insert the root path
                    sessionURLPrefix + id;
            }
        }

        if (URIUtil.isRelative(uri))
        {
            return  uri.substring(0, suffix) + sessionURLPrefix + id + uri.substring(suffix);
        }
        else
        {
            if (httpURI == null)
                httpURI = HttpURI.from(uri);

            return uri.substring(0, suffix) +
                ((HttpScheme.HTTPS.is(httpURI.getScheme()) || HttpScheme.HTTP.is(httpURI.getScheme())) && httpURI.getPath() == null ? "/" : "") + //if no path so insert the root path
                sessionURLPrefix + id + uri.substring(suffix);
        }
    }

    @Override
    public void onSessionIdChanged(Session session, String oldId)
    {
        for (Session.LifeCycleListener listener : _sessionLifeCycleListeners)
            listener.onSessionIdChanged(session, oldId);
    }

    @Override
    public void onSessionCreated(Session session)
    {
        for (Session.LifeCycleListener listener : _sessionLifeCycleListeners)
            listener.onSessionCreated(session);
    }

    @Override
    public void onSessionDestroyed(Session session)
    {
        for (Session.LifeCycleListener listener : _sessionLifeCycleListeners)
            listener.onSessionDestroyed(session);
    }

    /**
     * Called by SessionIdManager to remove a session that has been invalidated,
     * either by this context or another context. Also called by
     * SessionIdManager when a session has expired in either this context or
     * another context.
     *
     * @param id the session id to invalidate
     */
    @Override
    public void invalidate(String id) throws Exception
    {
        if (StringUtil.isBlank(id))
            return;
        try
        {
            // Remove the Session object from the session cache and any backing
            // data store
            ManagedSession session = _sessionCache.delete(id);
            if (session != null)
            {
                //start invalidating if it is not already begun, and call the listeners
                try
                {
                    if (session.beginInvalidate())
                    {
                        try
                        {
                            onSessionDestroyed(session);
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
     * @return True if absolute URLs are check for remoteness before being session encoded.
     */
    @Override
    public boolean isCheckingRemoteSessionIdEncoding()
    {
        return _checkingRemoteSessionIdEncoding;
    }

    /**
     * @param remote True if absolute URLs are check for remoteness before being session encoded.
     */
    @Override
    public void setCheckingRemoteSessionIdEncoding(boolean remote)
    {
        _checkingRemoteSessionIdEncoding = remote;
    }

    /**
     * @return true if session cookies should be HTTP only
     * @see HttpCookie#isHttpOnly()
     */
    @Override
    public boolean isHttpOnly()
    {
        return Boolean.parseBoolean(_sessionCookieAttributes.get(HttpCookie.HTTP_ONLY_ATTRIBUTE));
    }

    /**
     * Set if Session cookies should use HTTP Only
     *
     * @param httpOnly True if cookies should be HttpOnly.
     * @see HttpCookie
     */
    @Override
    public void setHttpOnly(boolean httpOnly)
    {
        _sessionCookieAttributes.put(HttpCookie.HTTP_ONLY_ATTRIBUTE, Boolean.toString(httpOnly));
    }
    
    /**
     * @return true if session cookies should have the {@code Partitioned} attribute
     * @see HttpCookie#isPartitioned()
     */
    @Override
    public boolean isPartitioned()
    {
        return Boolean.parseBoolean(_sessionCookieAttributes.get(HttpCookie.PARTITIONED_ATTRIBUTE));
    }

    /**
     * Sets whether session cookies should have the {@code Partitioned} attribute
     *
     * @param partitioned whether session cookies should have the {@code Partitioned} attribute
     * @see HttpCookie
     */
    @Override
    public void setPartitioned(boolean partitioned)
    {
        _sessionCookieAttributes.put(HttpCookie.PARTITIONED_ATTRIBUTE, Boolean.toString(partitioned));
    }

    /**
     * Check if id is in use by this context
     *
     * @param id identity of session to check
     * @return <code>true</code> if this manager knows about this id
     * @throws Exception if any error occurred
     */
    @Override
    public boolean isIdInUse(String id) throws Exception
    {
        //Ask the session store
        return _sessionCache.exists(id);
    }

    /**
     * @return same as SessionCookieConfig.getSecure(). If true, session
     * cookies are ALWAYS marked as secure. If false, a session cookie is
     * ONLY marked as secure if _secureRequestOnly == true and it is an HTTPS request.
     */
    @Override
    public boolean isSecureCookies()
    {
        return Boolean.parseBoolean(_sessionCookieAttributes.get(HttpCookie.SECURE_ATTRIBUTE));
    }
    
    @Override
    public void setSecureCookies(boolean secure)
    {
        _sessionCookieAttributes.put(HttpCookie.SECURE_ATTRIBUTE, Boolean.toString(secure));
        secureRequestOnlyAttributes();
    }

    /**
     * @return true if session cookie is to be marked as secure only on HTTPS requests
     */
    @Override
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
    @Override
    public void setSecureRequestOnly(boolean secureRequestOnly)
    {
        _secureRequestOnly = secureRequestOnly;
        secureRequestOnlyAttributes();
    }

    private void secureRequestOnlyAttributes()
    {
        if (isSecureRequestOnly() && !Boolean.parseBoolean(_sessionCookieAttributes.get(HttpCookie.SECURE_ATTRIBUTE)))
        {
            Map<String, String> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            attributes.putAll(_sessionCookieAttributes);
            attributes.put(HttpCookie.SECURE_ATTRIBUTE, Boolean.TRUE.toString());
            _sessionCookieSecureAttributes = attributes;
        }
        else
        {
            _sessionCookieSecureAttributes = _sessionCookieAttributes;
        }
    }
    
    /**
     * @return true if using session cookies is allowed, false otherwise
     */
    @Override
    public boolean isUsingCookies()
    {
        return _usingCookies;
    }
    
    /**
     * @param usingCookies true if cookies are used to track sessions
     */
    @Override
    public void setUsingCookies(boolean usingCookies)
    {
        _usingCookies = usingCookies;
    }

    /**
     * @return whether the session management is handled via URLs.
     */
    @Override
    public boolean isUsingUriParameters()
    {
        return _usingUriParameters;
    }

    public void setUsingUriParameters(boolean usingUriParameters)
    {
        _usingUriParameters = usingUriParameters;
    }

    /**
     * @deprecated use {@link #isUsingUriParameters()} instead, will be removed in Jetty 12.1.0
     */
    @Deprecated(since = "12.0.1", forRemoval = true)
    public boolean isUsingURLs()
    {
        return isUsingUriParameters();
    }

    /**
     * @deprecated use {@link #setUsingUriParameters(boolean)} instead, will be removed in Jetty 12.1.0
     */
    @Deprecated(since = "12.0.1", forRemoval = true)
    public void setUsingURLs(boolean usingURLs)
    {
        setUsingUriParameters(usingURLs);
    }

    /**
     * Create a new Session, using the requested session id if possible.
     * @param request the inbound request
     * @param requestedSessionId the session id used by the request
     */
    @Override
    public void newSession(Request request, String requestedSessionId, Consumer<ManagedSession> consumer)
    {
        long created = System.currentTimeMillis();
        String id = _sessionIdManager.newSessionId(request, requestedSessionId, created);
        ManagedSession session = _sessionCache.newSession(id, created, (_dftMaxIdleSecs > 0 ? _dftMaxIdleSecs * 1000L : -1));
        session.setExtendedId(_sessionIdManager.getExtendedId(id, request));
        session.getSessionData().setLastNode(_sessionIdManager.getWorkerName());
        try
        {
            _sessionCache.add(id, session);

            _sessionsCreatedStats.increment();

            if (request != null && request.getConnectionMetaData().isSecure())
                session.setAttribute(ManagedSession.SESSION_CREATED_SECURE, Boolean.TRUE);

            consumer.accept(session);
            onSessionCreated(session);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to add Session {}", id, e);
        }
    }
    
    /**
     * Make a new timer for the session.
     * @param session the session to time
     */
    @Override
    public SessionInactivityTimer newSessionInactivityTimer(ManagedSession session)
    {
        return new SessionInactivityTimer(this, session, _scheduler);
    }

    /**
     * Record length of time session has been active. Called when the
     * session is about to be invalidated.
     *
     * @param session the session whose time to record
     */
    @Override
    public void recordSessionTime(ManagedSession session)
    {
        _sessionTimeStats.record(Math.round((System.currentTimeMillis() - session.getSessionData().getCreated()) / 1000.0));
    }
    
    /**
     * Change the existing session id.
     *
     * @param oldId the old session id
     * @param oldExtendedId the session id including worker suffix
     * @param newId the new session id
     * @param newExtendedId the new session id including worker suffix
     */
    @Override
    public void renewSessionId(String oldId, String oldExtendedId, String newId, String newExtendedId)
    {
        ManagedSession session = null;
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
            onSessionIdChanged(session, oldId);
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
                    _sessionCache.release(session);
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to release {}", newId, e);
                }
            }
        }
    }

    /**
     * Called periodically by the HouseKeeper to handle the list of
     * sessions that have expired since the last call to scavenge.
     */
    @Override
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
     * <p>
     * If it has passed its expiry, then we will mark it for
     * scavenging by next run of the HouseKeeper; if it has
     * been idle longer than the configured eviction period,
     * we evict from the cache.
     * <p>
     * If none of the above are true, then the System timer
     * is inconsistent and the caller of this method will
     * need to reset the timer.
     *
     * @param session the session
     * @param now the time at which to check for expiry
     */
    @Override
    public void sessionTimerExpired(ManagedSession session, long now)
    {
        if (session == null)
            return;

        try (AutoLock ignored = session.lock())
        {
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
    
    protected void addSessionStreamWrapper(Request request)
    {
        request.addHttpStreamWrapper(s -> new SessionStreamWrapper(s, this, request));
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
        removeBean(_sessionLifeCycleListeners);
        _sessionLifeCycleListeners = Collections.emptyList();
    }

    /**
     * Find any Session associated with the Request.
     *
     * @param request The request from which to obtain the ID
     */
    protected RequestedSession resolveRequestedSessionId(Request request)
    {
        String requestedSessionId = null;
        boolean requestedSessionIdFromCookie = false;
        ManagedSession session = null;

        List<String> ids = null;

        //first try getting list of id from cookies
        if (isUsingCookies())
        {
            //Cookie[] cookies = request.getCookies();
            List<HttpCookie> cookies = Request.getCookies(request);
            if (cookies != null && cookies.size() > 0)
            {
                final String sessionCookie = getSessionCookie();
                for (HttpCookie cookie : cookies)
                {
                    if (sessionCookie.equalsIgnoreCase(cookie.getName()))
                    {
                        if (ids == null)
                            ids = new ArrayList<>();
                        ids.add(cookie.getValue());
                    }
                }
            }
        }
        int cookieIds = ids == null ? 0 : ids.size();

        //try getting id from a url
        if (isUsingUriParameters())
        {
            HttpURI uri = request.getHttpURI();
            String param = uri.getParam();
            if (StringUtil.isNotBlank(param))
            {
                String name = getSessionIdPathParameterName();
                String[] params = param.split(";");
                for (String p : params)
                {
                    if (!p.startsWith(name) || p.length() <= name.length() || p.charAt(name.length()) != '=')
                        continue;

                    if (ids == null)
                        ids = new ArrayList<>();
                    ids.add(p.substring(name.length() + 1).trim());
                }
            }
        }

        //try getting a session id for our context that has been newly created by another context
        if (request.getContext().isCrossContextDispatch(request))
        {
            String tmp = (String)request.getAttribute(DefaultSessionIdManager.__NEW_SESSION_ID);
            if (!StringUtil.isEmpty(tmp))
            {
                if (ids == null)
                    ids = new ArrayList<>();
                ids.add(tmp);
            }
        }

        if (ids == null)
            return NO_REQUESTED_SESSION;

        if (LOG.isDebugEnabled())
            LOG.debug("Got Session IDs {} from cookies {}", ids, cookieIds);

        for (int i = 0; i < ids.size(); i++)
        {
            String id = ids.get(i);
            if (session == null)
            {
                //we currently do not have a session selected, use this one if it is valid
                ManagedSession s = getManagedSession(id);
                if (s != null && s.isValid())
                {
                    //associate it with the request so its reference count is decremented as the
                    //request exits
                    requestedSessionId = id;
                    requestedSessionIdFromCookie = i < cookieIds;
                    session = s;

                    if (LOG.isDebugEnabled())
                        LOG.debug("Selected session {}", session);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No session found for session id {}", id);

                    //if we don't have a valid session id yet, just choose the first one
                    if (requestedSessionId == null)
                    {
                        requestedSessionId = id;
                        requestedSessionIdFromCookie = i < cookieIds;
                    }
                }
            }
            else if (session.getId().equals(getSessionIdManager().getId(id)))
            {
                //we already have a valid session and now have a duplicate ID for it
                if (LOG.isDebugEnabled())
                    LOG.debug(duplicateSession(
                        requestedSessionId, true, requestedSessionIdFromCookie,
                        id, false, i < cookieIds));
            }
            else
            {
                //we already have a valid session and now have an ID for a different session
                //load the session to see if it is valid or not
                ManagedSession s = getManagedSession(id);
                if (s != null && s.isValid())
                {
                    try
                    {
                        _sessionCache.release(session);
                    }
                    catch (Exception x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Error releasing duplicate valid session: {}", requestedSessionId);
                    }
                    try
                    {
                        _sessionCache.release(s);
                    }
                    catch (Exception x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Error releasing duplicate valid session: {}", id);
                    }

                    throw new BadMessageException(duplicateSession(
                        requestedSessionId, true, requestedSessionIdFromCookie,
                        id, true, i < cookieIds));
                }
                else if (LOG.isDebugEnabled())
                {
                    LOG.debug(duplicateSession(
                        requestedSessionId, true, requestedSessionIdFromCookie,
                        id, false, i < cookieIds));
                }
            }
        }

        return new RequestedSession((session != null && session.isValid()) ? session : null, requestedSessionId, requestedSessionIdFromCookie);
    }

    private static String duplicateSession(String id0, boolean valid0, boolean cookie0, String id1, boolean valid1, boolean cookie1)
    {
        return "Duplicate sessions: %s[%s,%s] & %s[%s,%s]".formatted(
            id0, valid0 ? "valid" : "unknown", cookie0 ? "cookie" : "param",
            id1, valid1 ? "valid" : "unknown", cookie1 ? "cookie" : "param");
    }

    /**
     * Prepare sessions for session manager shutdown
     */
    private void shutdownSessions()
    {
        _sessionCache.shutdown();
    }
    
    public record RequestedSession(ManagedSession session, String sessionId, boolean sessionIdFromCookie)
    {
    }

    private static final  RequestedSession NO_REQUESTED_SESSION = new RequestedSession(null, null, false);

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
     * @param requestIsSecure whether the client is accessing the server over a secure protocol (i.e. HTTPS).
     * @return if this <code>SessionManager</code> uses cookies, then this method will return a new
     * {@link HttpCookie cookie object} that should be set on the client in order to link future HTTP requests
     * with the <code>session</code>. If cookies are not in use, this method returns <code>null</code>.
     */
    @Override
    public HttpCookie getSessionCookie(ManagedSession session, boolean requestIsSecure)
    {
        if (isUsingCookies())
        {
            String name = getSessionCookie();
            if (name == null)
                name = _sessionCookieAttributes.get("name");
            if (name == null)
                name =  __DefaultSessionCookie;
            if (isSecureRequestOnly() && requestIsSecure && _sessionCookieSecureAttributes != null && _sessionCookieAttributes != _sessionCookieSecureAttributes)
                return session.generateSetCookie(name, _sessionCookieSecureAttributes);
            return session.generateSetCookie(name, _sessionCookieAttributes);
        }

        return null;
    }

    /**
     * StreamWrapper to intercept commit and complete events to ensure
     * session handling happens in context, with the request available.
     * This implementation assumes that a request only has a single session.
     */
    private class SessionStreamWrapper extends HttpStream.Wrapper
    {
        private final SessionManager _sessionManager;
        private final Request _request;
        private final org.eclipse.jetty.server.Context _context;

        public SessionStreamWrapper(HttpStream wrapped, SessionManager sessionManager, Request request)
        {
            super(wrapped);
            _sessionManager = sessionManager;
            _request = request;
            _context = _request.getContext();
        }

        @Override
        public void failed(Throwable x)
        {
            //Leave session
            _context.run(this::doComplete, _request);
            super.failed(x);
        }

        @Override
        public void send(MetaData.Request metadataRequest, MetaData.Response metadataResponse, boolean last, ByteBuffer content, Callback callback)
        {
            if (metadataResponse != null)
            {
                // Write out session
                _context.run(this::doCommit, _request);
            }
            super.send(metadataRequest, metadataResponse, last, content, callback);
        }

        @Override
        public void succeeded()
        {
            // Leave session
            _context.run(this::doComplete, _request);
            super.succeeded();
        }

        private void doCommit()
        {
            commit(_sessionManager.getManagedSession(_request));
        }

        private void doComplete()
        {
            complete(_sessionManager.getManagedSession(_request));
        }
    }
}

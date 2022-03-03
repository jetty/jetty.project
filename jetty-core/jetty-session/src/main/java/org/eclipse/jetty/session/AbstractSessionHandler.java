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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
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
public abstract class AbstractSessionHandler extends Handler.Wrapper implements SessionManager
{
    static final Logger LOG = LoggerFactory.getLogger(AbstractSessionHandler.class);

    /**
     * Setting of max inactive interval for new sessions
     * -1 means no timeout
     */
    protected int _dftMaxIdleSecs = -1;
    protected boolean _usingURLs;
    protected boolean _usingCookies = true;
    protected SessionIdManager _sessionIdManager;
    protected ClassLoader _loader;
    protected Context _context;
    protected SessionContext _sessionContext;
    protected SessionCache _sessionCache;
    protected Set<String> _candidateSessionIdsForExpiry = ConcurrentHashMap.newKeySet();
    protected Scheduler _scheduler;
    protected boolean _ownScheduler = false;
    protected boolean _httpOnly = false;
    protected String _sessionCookie = __DefaultSessionCookie;
    protected String _sessionIdPathParameterName = __DefaultSessionIdPathParameterName;
    protected String _sessionIdPathParameterNamePrefix = ";" + _sessionIdPathParameterName + "=";
    protected String _sessionDomain;
    protected String _sessionPath;
    protected String _sessionComment;
    protected boolean _secureCookies = false;
    protected boolean _secureRequestOnly = true;
    protected int _maxCookieAge = -1;
    protected int _refreshCookieAge;
    protected boolean _checkingRemoteSessionIdEncoding;
    private final SampleStatistic _sessionTimeStats = new SampleStatistic();
    private final CounterStatistic _sessionsCreatedStats = new CounterStatistic();
    
    public AbstractSessionHandler()
    {
    }
        
    public void doStart() throws Exception
    {
        //check if session management is set up, if not set up defaults
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

            _scheduler = server.getBean(Scheduler.class);
            if (_scheduler == null)
            {
                _scheduler = new ScheduledExecutorScheduler(String.format("Session-Scheduler-%x", hashCode()), false);
                _ownScheduler = true;
                _scheduler.start();
            }
        }

        _sessionContext = new SessionContext(this);
        _sessionCache.initialize(_sessionContext);
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
    
    public void setRefreshCookieAge(int ageInSeconds)
    {
        _refreshCookieAge = ageInSeconds;
    }

    /**
     * @param usingCookies 
     */
    public void setUsingCookies(boolean usingCookies)
    {
        _usingCookies = usingCookies;
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
    
    @ManagedAttribute("domain of the session cookie, or null for the default")
    public String getSessionDomain()
    {
        return _sessionDomain;
    }
    
    @ManagedAttribute("path of the session cookie, or null for default")
    public String getSessionPath()
    {
        return _sessionPath;
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

    @ManagedAttribute("if greater the zero, the time in seconds a session cookie will last for")
    public int getMaxCookieAge()
    {
        return _maxCookieAge;
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

    @ManagedAttribute("the set session cookie")
    public String getSessionCookie()
    {
        return _sessionCookie;
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
     * @return The sameSite setting for session cookies or null for no setting
     * @see HttpCookie#getSameSite()
     */
    
    @ManagedAttribute("SameSite setting for session cookies")
    public HttpCookie.SameSite getSameSite()
    {
        return HttpCookie.getSameSiteFromComment(_sessionComment);
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
     * Get a known existing session
     *
     * @param extendedId The session id, possibly imcluding worker name suffix.
     * @return the Session matching the id or null if none exists
     */
    public Session getSession(String extendedId)
    {
        String id = getSessionIdManager().getId(extendedId);
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
            
            if (session != null && !session.getExtendedId().equals(extendedId))
                session.setIdChanged(true);
            
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
    
    public Session newSession(Request request, String requestedSessionId)
    {   
        long created = System.currentTimeMillis();
        String id = _sessionIdManager.newSessionId(request, requestedSessionId, created);
        Session session = _sessionCache.newSession(id, created, (_dftMaxIdleSecs > 0 ? _dftMaxIdleSecs * 1000L : -1));
        session.setExtendedId(_sessionIdManager.getExtendedId(id, request));
        session.getSessionData().setLastNode(_sessionIdManager.getWorkerName());
        Session.Wrapper apiSession = newSessionAPIWrapper(session);
        assert apiSession.getSession() == session;
        assert session.getWrapper() == apiSession;
        try
        {
            _sessionCache.add(id, session);

            _sessionsCreatedStats.increment();

            if (request != null && request.getConnectionMetaData().isSecure())
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
    public void sessionExpired(Session session, long now)
    {
        if (session == null)
            return;

        try (AutoLock lock = session.lock())
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
            else
            {
                //possibly evict the session
                _sessionCache.checkInactiveSession(session);
            }
        }
    }
    
    /**
     * Called by SessionIdManager to remove a session that has been invalidated,
     * either by this context or another context. Also called by
     * SessionIdManager when a session has expired in either this context or
     * another context.
     *
     * @param id the session id to invalidate
     */
    public void invalidate(String id) throws Exception
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

    /**
     * @return whether the session management is handled via URLs.
     */
    public boolean isUsingURLs()
    {
        return _usingURLs;
    }
    
    /**
     * @return true if using session cookies is allowed, false otherwise
     */
    public boolean isUsingCookies()
    {
        return _usingCookies;
    }
    
    /**
     * Called when a request is finally leaving a session.
     *
     * @param session the session object
     */
    public void complete(Session session)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Complete called with session {}", session);

        if (session == null)
            return;
        try
        {
            _sessionCache.release(session.getId(), session);
        }
        catch (Exception e)
        {
            LOG.warn("Unable to release Session {}", session, e);
        }
    }

    /**
     * Called when a response is about to be committed.
     * We might take this opportunity to persist the session
     * so that any subsequent requests to other servers
     * will see the modifications.
     */
    public void commit(Session session)
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
    public HttpCookie access(Session session, boolean secure)
    {
        long now = System.currentTimeMillis();

        if (session.access(now))
        {
            // Do we need to refresh the cookie?
            if (isUsingCookies() &&
                (session.isIdChanged() ||
                    (getCookieMaxAge() > 0 && getRefreshCookieAge() > 0 &&
                        ((now - session.getCookieSetTime()) / 1000 > getRefreshCookieAge()))))
            {
                HttpCookie cookie = getSessionCookie(session, _context == null ? "/" : (_context.getContextPath()), secure);
                session.cookieSet();
                session.setIdChanged(false);
                return cookie;
            }
        }
        return null;
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

    public long calculateInactivityTimeout(String id, long timeRemaining, long maxInactiveMs)
    {
        long time = 0;

        int evictionPolicy = _sessionCache.getEvictionPolicy();
        if (maxInactiveMs <= 0)
        {
            // sessions are immortal, they never expire
            if (evictionPolicy < SessionCache.EVICT_ON_INACTIVITY)
            {
                // we do not want to evict inactive sessions
                time = -1;
                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} is immortal && no inactivity eviction", id);
            }
            else
            {
                // sessions are immortal but we want to evict after
                // inactivity
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
                // timeout is the time remaining until its expiry
                time = (timeRemaining > 0 ? timeRemaining : 0);
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
                // want to evict on idle: timer is lesser of the session's
                // expiration remaining and the time to evict
                time = (timeRemaining > 0 ? (Math.min(maxInactiveMs, TimeUnit.SECONDS.toMillis(evictionPolicy))) : 0);

                if (LOG.isDebugEnabled())
                    LOG.debug("Session {} timer set to lesser of maxInactive={} and inactivityEvict={}", id,
                        maxInactiveMs, evictionPolicy);
            }
        }

        return time;
    }
    
    public SessionInactivityTimer newSessionInactivityTimer(Session session)
    {
        return new SessionInactivityTimer(this, session, _scheduler);
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
     * @return the session cache
     */
    public SessionCache getSessionCache()
    {
        return _sessionCache;
    }

    /**
     * @param sessionIdManager The sessionIdManager used for cross context session management.
     */
    public void setSessionIdManager(SessionIdManager sessionIdManager)
    {
        updateBean(_sessionIdManager, sessionIdManager);
        _sessionIdManager = sessionIdManager;
    }
    
    public SessionIdManager getSessionIdManager()
    {
        return _sessionIdManager;
    }
    
    public Context getContext()
    {
        return _context;
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
    
    @ManagedAttribute("number of sessions created by this context")
    public int getSessionsCreated()
    {
        return (int)_sessionsCreatedStats.getCurrent();
    }
    
    /**
     * Record length of time session has been active. Called when the
     * session is about to be invalidated.
     *
     * @param session the session whose time to record
     */
    public void recordSessionTime(Session session)
    {
        _sessionTimeStats.record(Math.round((System.currentTimeMillis() - session.getSessionData().getCreated()) / 1000.0));
    }
}

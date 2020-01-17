//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;

/**
 * Session
 *
 * A heavy-weight Session object representing an HttpSession. Session objects
 * relating to a context are kept in a {@link SessionCache}. The purpose of the
 * SessionCache is to keep the working set of Session objects in memory so that
 * they may be accessed quickly, and facilitate the sharing of a Session object
 * amongst multiple simultaneous requests referring to the same session id.
 *
 * The {@link SessionHandler} coordinates the lifecycle of Session objects with
 * the help of the SessionCache.
 *
 * @see SessionHandler
 * @see org.eclipse.jetty.server.SessionIdManager
 */
public class Session implements SessionHandler.SessionIf
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    /**
     *
     */
    public static final String SESSION_CREATED_SECURE = "org.eclipse.jetty.security.sessionCreatedSecure";

    /**
     * State
     *
     * Validity states of a session
     */
    public enum State
    {
        VALID, INVALID, INVALIDATING, CHANGING
    }

    public enum IdState
    {
        SET, CHANGING
    }

    protected final SessionData _sessionData; // the actual data associated with
    // a session

    protected final SessionHandler _handler; // the manager of the session

    protected String _extendedId; // the _id plus the worker name

    protected long _requests;

    protected boolean _idChanged;

    protected boolean _newSession;

    protected State _state = State.VALID; // state of the session:valid,invalid
    // or being invalidated

    protected Locker _lock = new Locker(); // sync lock
    protected Condition _stateChangeCompleted = _lock.newCondition();
    protected boolean _resident = false;
    protected final SessionInactivityTimer _sessionInactivityTimer;

    /**
     * SessionInactivityTimer
     *
     * Each Session has a timer associated with it that fires whenever it has
     * been idle (ie not accessed by a request) for a configurable amount of
     * time, or the Session expires.
     *
     * @see SessionCache
     */
    public class SessionInactivityTimer
    {
        protected final CyclicTimeout _timer;

        public SessionInactivityTimer()
        {
            _timer = new CyclicTimeout((getSessionHandler().getScheduler()))
            {
                @Override
                public void onTimeoutExpired()
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Timer expired for session {}", getId());
                    long now = System.currentTimeMillis();
                    //handle what to do with the session after the timer expired
                    getSessionHandler().sessionInactivityTimerExpired(Session.this, now);
                    try (Lock lock = Session.this.lock())
                    {
                        //grab the lock and check what happened to the session: if it didn't get evicted and
                        //it hasn't expired, we need to reset the timer
                        if (Session.this.isResident() && Session.this.getRequests() <= 0 && Session.this.isValid() &&
                            !Session.this.isExpiredAt(now))
                        {
                            //session wasn't expired or evicted, we need to reset the timer
                            SessionInactivityTimer.this.schedule(Session.this.calculateInactivityTimeout(now));
                        }
                    }
                }
            };
        }

        /**
         * For backward api compatibility only.
         *
         * @see #schedule(long)
         */
        @Deprecated
        public void schedule()
        {
            schedule(calculateInactivityTimeout(System.currentTimeMillis()));
        }

        /**
         * @param time the timeout to set; -1 means that the timer will not be
         * scheduled
         */
        public void schedule(long time)
        {
            if (time >= 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("(Re)starting timer for session {} at {}ms", getId(), time);
                _timer.schedule(time, TimeUnit.MILLISECONDS);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Not starting timer for session {}", getId());
            }
        }

        public void cancel()
        {
            _timer.cancel();
            if (LOG.isDebugEnabled())
                LOG.debug("Cancelled timer for session {}", getId());
        }

        public void destroy()
        {
            _timer.destroy();
            if (LOG.isDebugEnabled())
                LOG.debug("Destroyed timer for session {}", getId());
        }
    }

    /**
     * Create a new session
     *
     * @param handler the SessionHandler that manages this session
     * @param request the request the session should be based on
     * @param data the session data
     */
    public Session(SessionHandler handler, HttpServletRequest request, SessionData data)
    {
        _handler = handler;
        _sessionData = data;
        _newSession = true;
        _sessionData.setDirty(true);
        _sessionInactivityTimer = new SessionInactivityTimer();
    }

    /**
     * Re-inflate an existing session from some eg persistent store.
     *
     * @param handler the SessionHandler managing the session
     * @param data the session data
     */
    public Session(SessionHandler handler, SessionData data)
    {
        _handler = handler;
        _sessionData = data;
        _sessionInactivityTimer = new SessionInactivityTimer();
    }

    /**
     * Returns the current number of requests that are active in the Session.
     *
     * @return the number of active requests for this session
     */
    public long getRequests()
    {
        try (Lock lock = _lock.lock())
        {
            return _requests;
        }
    }

    public void setExtendedId(String extendedId)
    {
        _extendedId = extendedId;
    }

    protected void cookieSet()
    {
        try (Lock lock = _lock.lock())
        {
            _sessionData.setCookieSet(_sessionData.getAccessed());
        }
    }

    protected void use()
    {
        try (Lock lock = _lock.lock())
        {
            _requests++;

            // temporarily stop the idle timer
            if (LOG.isDebugEnabled())
                LOG.debug("Session {} in use, stopping timer, active requests={}", getId(), _requests);
            _sessionInactivityTimer.cancel();
        }
    }

    protected boolean access(long time)
    {
        try (Lock lock = _lock.lock())
        {
            if (!isValid() || !isResident())
                return false;
            _newSession = false;
            long lastAccessed = _sessionData.getAccessed();
            _sessionData.setAccessed(time);
            _sessionData.setLastAccessed(lastAccessed);
            _sessionData.calcAndSetExpiry(time);
            if (isExpiredAt(time))
            {
                invalidate();
                return false;
            }
            return true;
        }
    }

    protected void complete()
    {
        try (Lock lock = _lock.lock())
        {
            _requests--;

            if (LOG.isDebugEnabled())
                LOG.debug("Session {} complete, active requests={}", getId(), _requests);

            // start the inactivity timer if necessary
            if (_requests == 0)
            {
                //update the expiry time to take account of the time all requests spent inside of the
                //session.
                long now = System.currentTimeMillis();
                _sessionData.calcAndSetExpiry(now);
                _sessionInactivityTimer.schedule(calculateInactivityTimeout(now));
            }
        }
    }

    /**
     * Check to see if session has expired as at the time given.
     *
     * @param time the time since the epoch in ms
     * @return true if expired
     */
    protected boolean isExpiredAt(long time)
    {
        try (Lock lock = _lock.lock())
        {
            return _sessionData.isExpiredAt(time);
        }
    }

    /**
     * Check if the Session has been idle longer than a number of seconds.
     *
     * @param sec the number of seconds
     * @return true if the session has been idle longer than the interval
     */
    protected boolean isIdleLongerThan(int sec)
    {
        long now = System.currentTimeMillis();
        try (Lock lock = _lock.lock())
        {
            return ((_sessionData.getAccessed() + (sec * 1000)) <= now);
        }
    }

    /**
     * Call binding and attribute listeners based on the new and old values of
     * the attribute.
     *
     * @param name name of the attribute
     * @param newValue new value of the attribute
     * @param oldValue previous value of the attribute
     * @throws IllegalStateException if no session manager can be find
     */
    protected void callSessionAttributeListeners(String name, Object newValue, Object oldValue)
    {
        if (newValue == null || !newValue.equals(oldValue))
        {
            if (oldValue != null)
                unbindValue(name, oldValue);
            if (newValue != null)
                bindValue(name, newValue);

            if (_handler == null)
                throw new IllegalStateException("No session manager for session " + _sessionData.getId());

            _handler.doSessionAttributeListeners(this, name, oldValue, newValue);
        }
    }

    /**
     * Unbind value if value implements {@link HttpSessionBindingListener}
     * (calls
     * {@link HttpSessionBindingListener#valueUnbound(HttpSessionBindingEvent)})
     *
     * @param name the name with which the object is bound or unbound
     * @param value the bound value
     */
    public void unbindValue(java.lang.String name, Object value)
    {
        if (value != null && value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(this, name));
    }

    /**
     * Bind value if value implements {@link HttpSessionBindingListener} (calls
     * {@link HttpSessionBindingListener#valueBound(HttpSessionBindingEvent)})
     *
     * @param name the name with which the object is bound or unbound
     * @param value the bound value
     */
    public void bindValue(java.lang.String name, Object value)
    {
        if (value != null && value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(this, name));
    }

    /**
     * Call the activation listeners. This must be called holding the lock.
     */
    public void didActivate()
    {
        //A passivate listener might remove a non-serializable attribute that
        //the activate listener might put back in again, which would spuriously
        //set the dirty bit to true, causing another round of passivate/activate
        //when the request exits. The store clears the dirty bit if it does a
        //save, so ensure dirty flag is set to the value determined by the store,
        //not a passivation listener.
        boolean dirty = getSessionData().isDirty();
        
        try 
        {
            HttpSessionEvent event = new HttpSessionEvent(this);
            for (Iterator<String> iter = _sessionData.getKeys().iterator(); iter.hasNext();)
            {
                Object value = _sessionData.getAttribute(iter.next());
                if (value instanceof HttpSessionActivationListener)
                {
                    HttpSessionActivationListener listener = (HttpSessionActivationListener)value;
                    listener.sessionDidActivate(event);
                }
            }
        }
        finally
        {
            getSessionData().setDirty(dirty);
        }
    }

    /**
     * Call the passivation listeners. This must be called holding the lock
     */
    public void willPassivate()
    {
        HttpSessionEvent event = new HttpSessionEvent(this);
        for (Iterator<String> iter = _sessionData.getKeys().iterator(); iter.hasNext();)
        {
            Object value = _sessionData.getAttribute(iter.next());
            if (value instanceof HttpSessionActivationListener)
            {
                HttpSessionActivationListener listener = (HttpSessionActivationListener)value;
                listener.sessionWillPassivate(event);
            }
        }
    }

    public boolean isValid()
    {
        try (Lock lock = _lock.lock())
        {
            return _state == State.VALID;
        }
    }

    public boolean isInvalid()
    {
        try (Lock lock = _lock.lock())
        {
            return _state == State.INVALID || _state == State.INVALIDATING;
        }
    }

    public boolean isChanging()
    {
        checkLocked();
        return _state == State.CHANGING;
    }

    public long getCookieSetTime()
    {
        try (Lock lock = _lock.lock())
        {
            return _sessionData.getCookieSet();
        }
    }

    @Override
    public long getCreationTime() throws IllegalStateException
    {
        try (Lock lock = _lock.lock())
        {
            checkValidForRead();
            return _sessionData.getCreated();
        }
    }

    /**
     * @see javax.servlet.http.HttpSession#getId()
     */
    @Override
    public String getId()
    {
        try (Lock lock = _lock.lock())
        {
            return _sessionData.getId();
        }
    }

    public String getExtendedId()
    {
        return _extendedId;
    }

    public String getContextPath()
    {
        return _sessionData.getContextPath();
    }

    public String getVHost()
    {
        return _sessionData.getVhost();
    }

    /**
     * @see javax.servlet.http.HttpSession#getLastAccessedTime()
     */
    @Override
    public long getLastAccessedTime()
    {
        try (Lock lock = _lock.lock())
        {
            if (isInvalid())
            {
                throw new IllegalStateException("Session not valid");
            }
            return _sessionData.getLastAccessed();
        }
    }

    /**
     * @see javax.servlet.http.HttpSession#getServletContext()
     */
    @Override
    public ServletContext getServletContext()
    {
        if (_handler == null)
            throw new IllegalStateException("No session manager for session " + _sessionData.getId());
        return _handler._context;
    }

    /**
     * @see javax.servlet.http.HttpSession#setMaxInactiveInterval(int)
     */
    @Override
    public void setMaxInactiveInterval(int secs)
    {
        try (Lock lock = _lock.lock())
        {
            _sessionData.setMaxInactiveMs((long)secs * 1000L);
            _sessionData.calcAndSetExpiry();
            //dirty metadata writes can be skipped, but changing the
            //maxinactiveinterval should write the session out because
            //it may affect the session on other nodes, or on the same
            //node in the case of the nullsessioncache
            _sessionData.setDirty(true);

            if (LOG.isDebugEnabled())
            {
                if (secs <= 0)
                    LOG.debug("Session {} is now immortal (maxInactiveInterval={})", _sessionData.getId(), secs);
                else
                    LOG.debug("Session {} maxInactiveInterval={}", _sessionData.getId(), secs);
            }
        }
    }

    @Deprecated
    public void updateInactivityTimer()
    {
        //for backward api compatibility only
    }

    /**
     * Calculate what the session timer setting should be based on:
     * the time remaining before the session expires
     * and any idle eviction time configured.
     * The timer value will be the lesser of the above.
     *
     * @param now the time at which to calculate remaining expiry
     * @return the time remaining before expiry or inactivity timeout
     */
    public long calculateInactivityTimeout(long now)
    {
        long time = 0;

        try (Lock lock = _lock.lock())
        {
            long remaining = _sessionData.getExpiry() - now;
            long maxInactive = _sessionData.getMaxInactiveMs();
            int evictionPolicy = getSessionHandler().getSessionCache().getEvictionPolicy();

            if (maxInactive <= 0)
            {
                // sessions are immortal, they never expire
                if (evictionPolicy < SessionCache.EVICT_ON_INACTIVITY)
                {
                    // we do not want to evict inactive sessions
                    time = -1;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} is immortal && no inactivity eviction", getId());
                }
                else
                {
                    // sessions are immortal but we want to evict after
                    // inactivity
                    time = TimeUnit.SECONDS.toMillis(evictionPolicy);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} is immortal; evict after {} sec inactivity", getId(), evictionPolicy);
                }
            }
            else
            {
                // sessions are not immortal
                if (evictionPolicy == SessionCache.NEVER_EVICT)
                {
                    // timeout is the time remaining until its expiry
                    time = (remaining > 0 ? remaining : 0);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} no eviction", getId());
                }
                else if (evictionPolicy == SessionCache.EVICT_ON_SESSION_EXIT)
                {
                    // session will not remain in the cache, so no timeout
                    time = -1;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} evict on exit", getId());
                }
                else
                {
                    // want to evict on idle: timer is lesser of the session's
                    // expiration remaining and the time to evict
                    time = (remaining > 0 ? (Math.min(maxInactive, TimeUnit.SECONDS.toMillis(evictionPolicy))) : 0);

                    if (LOG.isDebugEnabled())
                        LOG.debug("Session {} timer set to lesser of maxInactive={} and inactivityEvict={}", getId(),
                            maxInactive, evictionPolicy);
                }
            }
        }

        return time;
    }

    /**
     * @see javax.servlet.http.HttpSession#getMaxInactiveInterval()
     */
    @Override
    public int getMaxInactiveInterval()
    {
        try (Lock lock = _lock.lock())
        {
            long maxInactiveMs = _sessionData.getMaxInactiveMs();
            return (int)(maxInactiveMs < 0 ? -1 : maxInactiveMs / 1000);
        }
    }

    /**
     * @see javax.servlet.http.HttpSession#getSessionContext()
     */
    @Override
    @Deprecated
    public HttpSessionContext getSessionContext()
    {
        checkValidForRead();
        return SessionHandler.__nullSessionContext;
    }

    public SessionHandler getSessionHandler()
    {
        return _handler;
    }

    /**
     * Check that the session can be modified.
     *
     * @throws IllegalStateException if the session is invalid
     */
    protected void checkValidForWrite() throws IllegalStateException
    {
        checkLocked();

        if (_state == State.INVALID)
            throw new IllegalStateException("Not valid for write: id=" + _sessionData.getId() +
                " created=" + _sessionData.getCreated() +
                " accessed=" + _sessionData.getAccessed() +
                " lastaccessed=" + _sessionData.getLastAccessed() +
                " maxInactiveMs=" + _sessionData.getMaxInactiveMs() +
                " expiry=" + _sessionData.getExpiry());

        if (_state == State.INVALIDATING)
            return; // in the process of being invalidated, listeners may try to
        // remove attributes

        if (!isResident())
            throw new IllegalStateException("Not valid for write: id=" + _sessionData.getId() + " not resident");
    }

    /**
     * Chech that the session data can be read.
     *
     * @throws IllegalStateException if the session is invalid
     */
    protected void checkValidForRead() throws IllegalStateException
    {
        checkLocked();

        if (_state == State.INVALID)
            throw new IllegalStateException("Invalid for read: id=" + _sessionData.getId() +
                " created=" + _sessionData.getCreated() +
                " accessed=" + _sessionData.getAccessed() +
                " lastaccessed=" + _sessionData.getLastAccessed() +
                " maxInactiveMs=" + _sessionData.getMaxInactiveMs() +
                " expiry=" + _sessionData.getExpiry());

        if (_state == State.INVALIDATING)
            return;

        if (!isResident())
            throw new IllegalStateException("Invalid for read: id=" + _sessionData.getId() + " not resident");
    }

    protected void checkLocked() throws IllegalStateException
    {
        if (!_lock.isLocked())
            throw new IllegalStateException("Session not locked");
    }

    /**
     * @see javax.servlet.http.HttpSession#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        try (Lock lock = _lock.lock())
        {
            checkValidForRead();
            return _sessionData.getAttribute(name);
        }
    }

    /**
     * @see javax.servlet.http.HttpSession#getValue(java.lang.String)
     */
    @Override
    @Deprecated
    public Object getValue(String name)
    {
        try (Lock lock = _lock.lock())
        {
            checkValidForRead();
            return _sessionData.getAttribute(name);
        }
    }

    /**
     * @see javax.servlet.http.HttpSession#getAttributeNames()
     */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        try (Lock lock = _lock.lock())
        {
            checkValidForRead();
            final Iterator<String> itor = _sessionData.getKeys().iterator();
            return new Enumeration<String>()
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
    }

    public int getAttributes()
    {
        return _sessionData.getKeys().size();
    }

    public Set<String> getNames()
    {
        return Collections.unmodifiableSet(_sessionData.getKeys());
    }

    /**
     * @deprecated As of Version 2.2, this method is replaced by
     * {@link #getAttributeNames}
     */
    @Deprecated
    @Override
    public String[] getValueNames() throws IllegalStateException
    {
        try (Lock lock = _lock.lock())
        {
            checkValidForRead();
            Iterator<String> itor = _sessionData.getKeys().iterator();
            if (!itor.hasNext())
                return new String[0];
            ArrayList<String> names = new ArrayList<>();
            while (itor.hasNext())
            {
                names.add(itor.next());
            }
            return names.toArray(new String[names.size()]);
        }
    }

    /**
     * @see javax.servlet.http.HttpSession#setAttribute(java.lang.String,
     * java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object old = null;
        try (Lock lock = _lock.lock())
        {
            // if session is not valid, don't accept the set
            checkValidForWrite();
            old = _sessionData.setAttribute(name, value);
        }
        if (value == null && old == null)
            return; // if same as remove attribute but attribute was already
        // removed, no change
        callSessionAttributeListeners(name, value, old);
    }

    /**
     * @see javax.servlet.http.HttpSession#putValue(java.lang.String,
     * java.lang.Object)
     */
    @Override
    @Deprecated
    public void putValue(String name, Object value)
    {
        setAttribute(name, value);
    }

    /**
     * @see javax.servlet.http.HttpSession#removeAttribute(java.lang.String)
     */
    @Override
    public void removeAttribute(String name)
    {
        setAttribute(name, null);
    }

    /**
     * @see javax.servlet.http.HttpSession#removeValue(java.lang.String)
     */
    @Override
    @Deprecated
    public void removeValue(String name)
    {
        setAttribute(name, null);
    }

    /**
     * Force a change to the id of a session.
     *
     * @param request the Request associated with the call to change id.
     */
    public void renewId(HttpServletRequest request)
    {
        if (_handler == null)
            throw new IllegalStateException("No session manager for session " + _sessionData.getId());

        String id = null;
        String extendedId = null;
        try (Lock lock = _lock.lock())
        {
            while (true)
            {
                switch (_state)
                {
                    case INVALID:
                    case INVALIDATING:
                        throw new IllegalStateException();

                    case CHANGING:
                        try
                        {
                            _stateChangeCompleted.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        continue;

                    case VALID:
                        _state = State.CHANGING;
                        break;
                    default:
                        throw new IllegalStateException();
                }
                break;
            }

            id = _sessionData.getId(); // grab the values as they are now
            extendedId = getExtendedId();
        }

        String newId = _handler._sessionIdManager.renewSessionId(id, extendedId, request);

        try (Lock lock = _lock.lock())
        {
            switch (_state)
            {
                case CHANGING:
                    if (id.equals(newId))
                        throw new IllegalStateException("Unable to change session id");

                    // this shouldn't be necessary to do here EXCEPT that when a
                    // null session cache is
                    // used, a new Session object will be created during the
                    // call to renew, so this
                    // Session object will not have been modified.
                    _sessionData.setId(newId);
                    setExtendedId(_handler._sessionIdManager.getExtendedId(newId, request));
                    setIdChanged(true);

                    _state = State.VALID;
                    _stateChangeCompleted.signalAll();
                    break;

                case INVALID:
                case INVALIDATING:
                    throw new IllegalStateException("Session invalid");

                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * Called by users to invalidate a session, or called by the access method
     * as a request enters the session if the session has expired, or called by
     * manager as a result of scavenger expiring session
     *
     * @see javax.servlet.http.HttpSession#invalidate()
     */
    @Override
    public void invalidate()
    {
        if (_handler == null)
            throw new IllegalStateException("No session manager for session " + _sessionData.getId());

        boolean result = beginInvalidate();

        try
        {
            // if the session was not already invalid, or in process of being
            // invalidated, do invalidate
            if (result)
            {
                try
                {
                    // do the invalidation
                    _handler.callSessionDestroyedListeners(this);
                }
                finally
                {
                    // call the attribute removed listeners and finally mark it
                    // as invalid
                    finishInvalidate();
                }
                // tell id mgr to remove sessions with same id from all contexts
                _handler.getSessionIdManager().invalidateAll(_sessionData.getId());
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /**
     * Grab the lock on the session
     *
     * @return the lock
     */
    public Lock lock()
    {
        return _lock.lock();
    }

    /**
     * @return true if the session is not already invalid or being invalidated.
     */
    protected boolean beginInvalidate()
    {
        boolean result = false;

        try (Lock lock = _lock.lock())
        {

            while (true)
            {
                switch (_state)
                {
                    case INVALID:
                    {
                        throw new IllegalStateException(); // spec does not
                        // allow invalidate
                        // of already invalid
                        // session
                    }
                    case INVALIDATING:
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Session {} already being invalidated", _sessionData.getId());
                        break;
                    }
                    case CHANGING:
                    {
                        try
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Session {} waiting for id change to complete", _sessionData.getId());
                            _stateChangeCompleted.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        continue;
                    }
                    case VALID:
                    {
                        // only first change from valid to invalidating should
                        // be actionable
                        result = true;
                        _state = State.INVALIDATING;
                        break;
                    }
                    default:
                        throw new IllegalStateException();
                }
                break;
            }
        }

        return result;
    }

    /**
     * Call HttpSessionAttributeListeners as part of invalidating a Session.
     *
     * @throws IllegalStateException if no session manager can be find
     */
    @Deprecated
    protected void doInvalidate() throws IllegalStateException
    {
        finishInvalidate();
    }

    /**
     * Call HttpSessionAttributeListeners as part of invalidating a Session.
     *
     * @throws IllegalStateException if no session manager can be find
     */
    protected void finishInvalidate() throws IllegalStateException
    {
        try (Lock lock = _lock.lock())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("invalidate {}", _sessionData.getId());
                if (_state == State.VALID || _state == State.INVALIDATING)
                {
                    Set<String> keys = null;
                    do
                    {
                        keys = _sessionData.getKeys();
                        for (String key : keys)
                        {
                            Object old = _sessionData.setAttribute(key, null);
                            // if same as remove attribute but attribute was
                            // already removed, no change
                            if (old == null)
                                continue;
                            callSessionAttributeListeners(key, null, old);
                        }
                    }
                    while (!keys.isEmpty());
                }
            }
            finally
            {
                // mark as invalid
                _state = State.INVALID;
                _handler.recordSessionTime(this);
                _stateChangeCompleted.signalAll();
            }
        }
    }

    @Override
    public boolean isNew() throws IllegalStateException
    {
        try (Lock lock = _lock.lock())
        {
            checkValidForRead();
            return _newSession;
        }
    }

    public void setIdChanged(boolean changed)
    {
        try (Lock lock = _lock.lock())
        {
            _idChanged = changed;
        }
    }

    public boolean isIdChanged()
    {
        try (Lock lock = _lock.lock())
        {
            return _idChanged;
        }
    }

    @Override
    public Session getSession()
    {
        // TODO why is this used
        return this;
    }

    protected SessionData getSessionData()
    {
        return _sessionData;
    }

    /**
     *
     */
    public void setResident(boolean resident)
    {
        _resident = resident;

        if (!_resident)
            _sessionInactivityTimer.destroy();
    }

    public boolean isResident()
    {
        return _resident;
    }

    @Override
    public String toString()
    {
        try (Lock lock = _lock.lock())
        {
            return String.format("%s@%x{id=%s,x=%s,req=%d,res=%b}",
                getClass().getSimpleName(),
                hashCode(),
                _sessionData.getId(),
                _extendedId,
                _requests,
                _resident);
        }
    }
}

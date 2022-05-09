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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.locks.Condition;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session
 *
 * A heavy-weight Session object representing an HttpSession. Session objects
 * relating to a context are kept in a {@link SessionCache}. The purpose of the
 * SessionCache is to keep the working set of Session objects in memory so that
 * they may be accessed quickly, and facilitate the sharing of a Session object
 * amongst multiple simultaneous requests referring to the same session id.
 *
 * The {@link SessionManager} coordinates the lifecycle of Session objects with
 * the help of the SessionCache/SessionDataStore.
 *
 * @see SessionManager
 * @see org.eclipse.jetty.session.SessionIdManager
 */
public class Session
{
    public interface APISession
    {
        Session getCoreSession();
    }

    private static final Logger LOG = LoggerFactory.getLogger(Session.class);

    /**
     * Attribute set if the session is secure
     */
    public static final String SESSION_CREATED_SECURE = "org.eclipse.jetty.security.sessionCreatedSecure";

    /**
     * Validity states of a session
     */
    public enum State
    {
        VALID, INVALID, INVALIDATING, CHANGING
    }

    /**
     * State of the session id
     *
     */
    public enum IdState
    {
        SET, CHANGING
    }
    
    private Object _apiSession;

    protected final SessionData _sessionData; // the actual data associated with
    // a session

    protected final SessionManager _manager; // the manager of the session

    protected String _extendedId; // the _id plus the worker name

    protected long _requests;

    protected boolean _needSetCookie;

    protected boolean _newSession;

    protected State _state = State.VALID; // state of the session:valid,invalid
    // or being invalidated

    protected AutoLock _lock = new AutoLock();
    protected Condition _stateChangeCompleted = _lock.newCondition();
    protected boolean _resident = false;
    protected final SessionInactivityTimer _sessionInactivityTimer;

    /**
     * Create a new session object. The session could be an 
     * entirely new session, or could be being re-inflated from
     * persistent store.
     *
     * @param manager the SessionHandler that manages this session
     * @param data the session data
     */
    public Session(SessionManager manager, SessionData data)
    {
        _manager = manager;
        _sessionData = data;
        if (_sessionData.getLastSaved() <= 0)
        {
            _newSession = true;
            _sessionData.setDirty(true);
        }
        _sessionInactivityTimer = manager.newSessionInactivityTimer(this);
        _apiSession = _manager.newSessionAPIWrapper(this);
    }

    public static Session getSession(Object session)
    {
        if (session instanceof APISession wrapper)
            return wrapper.getCoreSession();
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getAPISession()
    {
        return (T)_apiSession;
    }
    
    public void setAPISession(Object o)
    {
        _apiSession = o;
    }
    
    /**
     * Returns the current number of requests that are active in the Session.
     *
     * @return the number of active requests for this session
     */
    public long getRequests()
    {
        try (AutoLock l = _lock.lock())
        {
            return _requests;
        }
    }

    public void setExtendedId(String extendedId)
    {
        _extendedId = extendedId;
    }

    /**
     * Set the time that the cookie was set and clear the idChanged flag.
     */
    void onSetCookieGenerated()
    {
        try (AutoLock l = _lock.lock())
        {
            _sessionData.setCookieSet(_sessionData.getAccessed());
            _needSetCookie = false;
        }
    }

    protected void use()
    {
        try (AutoLock l = _lock.lock())
        {
            _requests++;

            // temporarily stop the idle timer
            if (LOG.isDebugEnabled())
                LOG.debug("Session {} in use, stopping timer, active requests={}", getId(), _requests);
            _sessionInactivityTimer.cancel();
        }
    }

    public boolean access(long time)
    {
        try (AutoLock l = _lock.lock())
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

    public void commit()
    {
        _manager.commit(this);
    }

    public void complete()
    {
        _manager.complete(this);
    }

    void release()
    {
        try (AutoLock l = _lock.lock())
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
    public boolean isExpiredAt(long time)
    {
        try (AutoLock l = _lock.lock())
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
        try (AutoLock l = _lock.lock())
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
                _manager.callUnboundBindingListener(this, name, oldValue);
            if (newValue != null)
                _manager.callBoundBindingListener(this, name, newValue);

            if (_manager == null)
                throw new IllegalStateException("No session manager for session " + _sessionData.getId());

            _manager.callSessionAttributeListeners(this, name, oldValue, newValue);
        }
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
            for (String name : _sessionData.getKeys())
            {
                Object value = _sessionData.getAttribute(name);
                
                _manager.callSessionActivationListener(this, name, value);
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
        for (String name : _sessionData.getKeys())
        {
            Object value = _sessionData.getAttribute(name);
            _manager.callSessionPassivationListener(this, name, value);
        }
    }

    public boolean isValid()
    {
        try (AutoLock l = _lock.lock())
        {
            return _state == State.VALID;
        }
    }

    public boolean isInvalid()
    {
        try (AutoLock l = _lock.lock())
        {
            return _state == State.INVALID || _state == State.INVALIDATING;
        }
    }

    public long getCookieSetTime()
    {
        try (AutoLock l = _lock.lock())
        {
            return _sessionData.getCookieSet();
        }
    }

    public long getCreationTime() throws IllegalStateException
    {
        try (AutoLock l = _lock.lock())
        {
            checkValidForRead();
            return _sessionData.getCreated();
        }
    }

    public String getId()
    {
        try (AutoLock l = _lock.lock())
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

    public long getLastAccessedTime()
    {
        try (AutoLock l = _lock.lock())
        {
            checkValidForRead();
            return _sessionData.getLastAccessed();
        }
    }

    public void setMaxInactiveInterval(int secs)
    {
        try (AutoLock l = _lock.lock())
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

        try (AutoLock l = _lock.lock())
        {
            time = getSessionManager().calculateInactivityTimeout(getId(), _sessionData.getExpiry() - now, _sessionData.getMaxInactiveMs());
        }
        return time;
    }

    public int getMaxInactiveInterval()
    {
        try (AutoLock l = _lock.lock())
        {
            long maxInactiveMs = _sessionData.getMaxInactiveMs();
            return (int)(maxInactiveMs < 0 ? -1 : maxInactiveMs / 1000);
        }
    }

    public SessionManager getSessionManager()
    {
        return _manager;
    }

    /**
     * Check that the session can be modified.
     *
     * @throws IllegalStateException if the session is invalid
     */
    protected void checkValidForWrite() throws IllegalStateException
    {
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

    public Object getAttribute(String name)
    {
        try (AutoLock l = _lock.lock())
        {
            checkValidForRead();
            return _sessionData.getAttribute(name);
        }
    }

    public int getAttributes()
    {
        return _sessionData.getKeys().size();
    }

    public Set<String> getNames()
    {
        try (AutoLock l = _lock.lock())
        {
            checkValidForRead();
            return Collections.unmodifiableSet(_sessionData.getKeys());
        }
    }

    public void setAttribute(String name, Object value)
    {
        Object old = null;
        try (AutoLock l = _lock.lock())
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

    public void removeAttribute(String name)
    {
        setAttribute(name, null);
    }

    /**
     * Force a change to the id of a session.
     *
     * @param request the Request associated with the call to change id.
     */
    public void renewId(Request request)
    {
        if (_manager == null)
            throw new IllegalStateException("No session manager for session " + _sessionData.getId());

        String id = null;
        String extendedId = null;
        try (AutoLock l = _lock.lock())
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

        String newId = _manager.getSessionIdManager().renewSessionId(id, extendedId, request);

        try (AutoLock l = _lock.lock())
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
                    setExtendedId(_manager.getSessionIdManager().getExtendedId(newId, request));
                    onIdChanged();

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
     */

    public void invalidate()
    {
        if (_manager == null)
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
                    _manager.callSessionDestroyedListeners(this);
                }
                catch (Exception e)
                {
                    LOG.warn("Error during Session destroy listener", e);
                }
                finally
                {
                    // call the attribute removed listeners and finally mark it
                    // as invalid
                    finishInvalidate();
                    // tell id mgr to remove sessions with same id from all contexts
                    _manager.getSessionIdManager().invalidateAll(_sessionData.getId());
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to invalidate Session {}", this, e);
        }
    }

    /**
     * Grab the lock on the session
     *
     * @return the lock
     */
    public AutoLock lock()
    {
        return _lock.lock();
    }

    /**
     * @return true if the session is not already invalid or being invalidated.
     */
    public boolean beginInvalidate()
    {
        boolean result = false;

        try (AutoLock l = _lock.lock())
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
    public void finishInvalidate() throws IllegalStateException
    {
        try (AutoLock l = _lock.lock())
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
                _manager.recordSessionTime(this);
                _stateChangeCompleted.signalAll();
            }
        }
    }

    public boolean isNew() throws IllegalStateException
    {
        try (AutoLock l = _lock.lock())
        {
            checkValidForRead();
            return _newSession;
        }
    }

    public void onIdChanged()
    {
        try (AutoLock l = _lock.lock())
        {
            if (getSessionManager().isUsingCookies())
                _needSetCookie = true;
        }
    }

    public boolean isSetCookieNeeded()
    {
        try (AutoLock l = _lock.lock())
        {
            return _needSetCookie;
        }
    }

    public SessionData getSessionData()
    {
        return _sessionData;
    }

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
        try (AutoLock l = _lock.lock())
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

//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Locker.Lock;




/**
 * Session
 *
 *
 */
public class Session implements SessionManager.SessionIf
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    
    /**
     * 
     */
    public final static String SESSION_CREATED_SECURE="org.eclipse.jetty.security.sessionCreatedSecure";
    
    
    /**
     * State
     *
     * Validity states of a session
     */
    public enum State {VALID, INVALID, INVALIDATING};
    
    
    /**
     * PassivationState
     *
     * States of a session - either active in memory or passivated to persistent store
     */
    public enum PassivationState {PASSIVATED, ACTIVE};

    
    protected SessionData _sessionData; //the actual data associated with a session
    protected SessionManager _manager; //the manager of the session
    protected String _extendedId; //the _id plus the worker name
    protected long _requests;
    private boolean _idChanged; 
    private boolean _newSession;
    private State _state = State.VALID; //state of the session:valid,invalid or being invalidated
    private Locker _lock = new Locker(); //sync lock
    private PassivationState _passivationState = PassivationState.ACTIVE; //passivated or not
    private InspectionTimeout _inspectionTimeout = null;
    
    

    /* ------------------------------------------------------------- */
    /**
     * InspectionTimeout
     *
     *
     */
    public class InspectionTimeout extends IdleTimeout
    {


        public InspectionTimeout()
        {
            super(getSessionManager().getScheduler());
        }

        /** 
         * @see org.eclipse.jetty.io.IdleTimeout#onIdleExpired(java.util.concurrent.TimeoutException)
         */
        @Override
        protected void onIdleExpired(TimeoutException timeout)
        {
            //called when the timer goes off
            if (LOG.isDebugEnabled()) LOG.debug("Timer expired for session {}", getId());
           getSessionManager().inspect(Session.this);
        }

        /** 
         * @see org.eclipse.jetty.io.IdleTimeout#isOpen()
         */
        @Override
        public boolean isOpen()
        {
            // Called to determine if the timer should be reset
            // True if:
            // 1. the session is still valid
            // BUT if passivated out to disk, do we really want this timer to keep going off?
            try (Lock lock = _lock.lockIfNotHeld())
            {
                return isValid() && !isPassivated();
            }
        }

        /** 
         * @see org.eclipse.jetty.io.IdleTimeout#setIdleTimeout(long)
         */
        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            if (LOG.isDebugEnabled()) LOG.debug("setIdleTimeout called: "+idleTimeout);
            super.setIdleTimeout(idleTimeout);
        }

    }



    /* ------------------------------------------------------------- */
    /**
     * Create a new session
     * 
     * @param request the request the session should be based on
     * @param data the session data
     */
    public Session (HttpServletRequest request, SessionData data)
    {
        _sessionData = data;
        _newSession = true;
        _requests = 1; //access will not be called on this new session, but we are obviously in a request
    }
    
    

    /* ------------------------------------------------------------- */
    /**
     * Re-create an existing session
     * @param data the session data
     */
    public Session (SessionData data)
    {
        _sessionData = data;
    }
    

    /* ------------------------------------------------------------- */
    /**
     * Should call this method with a lock held if you want to
     * make decision on what to do with the session
     * 
     * @return the number of active requests for this session
     */
    public long getRequests()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return _requests;
        }
    }
    
    

    /* ------------------------------------------------------------- */
    public void setSessionManager (SessionManager manager)
    {
        _manager = manager;
    }
    

    /* ------------------------------------------------------------- */
    public void setExtendedId (String extendedId)
    {
        _extendedId = extendedId;
    }
    
    /* ------------------------------------------------------------- */
    protected void cookieSet()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
           _sessionData.setCookieSet(_sessionData.getAccessed());
        }
    }
    /* ------------------------------------------------------------ */
    protected boolean access(long time)
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            if (!isValid())
                return false;
            _newSession=false;
            long lastAccessed = _sessionData.getAccessed();
            _sessionData.setAccessed(time);
            _sessionData.setLastAccessed(lastAccessed);
            int maxInterval=getMaxInactiveInterval();
           _sessionData.setExpiry(maxInterval <= 0 ? 0 : (time + maxInterval*1000L));
            if (isExpiredAt(time))
            {
                invalidate();
                return false;
            }
            _requests++;
            return true;
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void complete()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            _requests--;
        }
    }

    

    /* ------------------------------------------------------------- */
    /** Check to see if session has expired as at the time given.
     * @param time the time in milliseconds
     * @return true if expired
     */
    protected boolean isExpiredAt(long time)
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return _sessionData.isExpiredAt(time);
        }
    }
    
    
    protected boolean isIdleLongerThan (int sec)
    {
        long now = System.currentTimeMillis();
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return ((_sessionData.getAccessed() + (sec*1000)) < now);
        }
    }
    
  

    /* ------------------------------------------------------------ */
    /**
     * Call binding and attribute listeners based on the new and old
     * values of the attribute.
     * 
     * @param name name of the attribute
     * @param newValue  new value of the attribute
     * @param oldValue previous value of the attribute
     */
    protected void callSessionAttributeListeners (String name, Object newValue, Object oldValue)
    {
        if (newValue==null || !newValue.equals(oldValue))
        {
            if (oldValue!=null)
                unbindValue(name,oldValue);
            if (newValue!=null)
                bindValue(name,newValue);

            if (_manager == null)
                throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());
            _manager.doSessionAttributeListeners(this,name,oldValue,newValue);
        }
    }

    
    
    /* ------------------------------------------------------------- */
    /**
     * Unbind value if value implements {@link HttpSessionBindingListener} (calls {@link HttpSessionBindingListener#valueUnbound(HttpSessionBindingEvent)}) 
     * @param name the name with which the object is bound or unbound  
     * @param value the bound value
     */
    public void unbindValue(java.lang.String name, Object value)
    {
        if (value!=null&&value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(this,name));
    }
    

    /* ------------------------------------------------------------- */
    /** 
     * Bind value if value implements {@link HttpSessionBindingListener} (calls {@link HttpSessionBindingListener#valueBound(HttpSessionBindingEvent)}) 
     * @param name the name with which the object is bound or unbound  
     * @param value the bound value
     */
    public void bindValue(java.lang.String name, Object value)
    {
        if (value!=null&&value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(this,name));
    }


    /* ------------------------------------------------------------- */
    /**
     * Call the activation listeners. This must be called holding the
     * _lock.
     */
    public void didActivate()
    {
        HttpSessionEvent event = new HttpSessionEvent(this);
        for (Iterator<String> iter = _sessionData.getKeys().iterator(); iter.hasNext();)
        {
            Object value = _sessionData.getAttribute(iter.next());
            if (value instanceof HttpSessionActivationListener)
            {
                HttpSessionActivationListener listener = (HttpSessionActivationListener) value;
                listener.sessionDidActivate(event);
            }
        }
    }


    /* ------------------------------------------------------------- */
    /**
     * Call the passivation listeners. This must be called holding the
     * _lock
     */
    public void willPassivate()
    {
        HttpSessionEvent event = new HttpSessionEvent(this);
        for (Iterator<String> iter = _sessionData.getKeys().iterator(); iter.hasNext();)
        {
            Object value = _sessionData.getAttribute(iter.next());
            if (value instanceof HttpSessionActivationListener)
            {
                HttpSessionActivationListener listener = (HttpSessionActivationListener) value;
                listener.sessionWillPassivate(event);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isValid()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return _state==State.VALID;
        }
    }


    /* ------------------------------------------------------------- */
    public long getCookieSetTime()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return _sessionData.getCookieSet();
        }
    }


    /* ------------------------------------------------------------- */
    @Override
    public long getCreationTime() throws IllegalStateException
    {
        try (Lock lock = _lock.lockIfNotHeld())
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
        try (Lock lock = _lock.lockIfNotHeld())
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

    
    public String getVHost ()
    {
        return _sessionData.getVhost();
    }
    

    /** 
     * @see javax.servlet.http.HttpSession#getLastAccessedTime()
     */
    @Override
    public long getLastAccessedTime()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return _sessionData.getLastAccessed();
        }
    }

    /** 
     * @see javax.servlet.http.HttpSession#getServletContext()
     */
    @Override
    public ServletContext getServletContext()
    {
        if (_manager == null)
            throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());
       return _manager._context;
    }

    /** 
     * @see javax.servlet.http.HttpSession#setMaxInactiveInterval(int)
     */
    @Override
    public void setMaxInactiveInterval(int secs)
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            _sessionData.setMaxInactiveMs((long)secs*1000L);  
            _sessionData.setExpiry(_sessionData.calcExpiry());
            _sessionData.setDirty(true);
            setTimeout();
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
     * 
     */
    public void setTimeout ()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            if (LOG.isDebugEnabled())LOG.debug("Set timeout called");
            long maxInactive =  _sessionData.getMaxInactiveMs();
            long maxIdle = TimeUnit.SECONDS.toMillis(getSessionManager().getSessionStore().getIdlePassivationTimeoutSec());


            if (maxInactive <= 0 && maxIdle <=0)
            {
                //session is immortal and idle passivation is not supported
                if (_inspectionTimeout != null)
                    _inspectionTimeout.setIdleTimeout(-1);
                
                if (LOG.isDebugEnabled()) LOG.debug("Session maxInactive <= 0 && idlePassivation <=0: timer cancelled");
                return;
            }

            if (_inspectionTimeout == null)
                _inspectionTimeout = new InspectionTimeout();

            //set the inspection timer to the smaller of the maxIdle interval or the idlePassivation interval
            long timeout = 0;
            if (maxInactive <= 0)
                timeout = maxIdle;
            else if (maxIdle <= 0)
                timeout = maxInactive;
            else
               timeout = Math.min(maxInactive, maxIdle);
            
            _inspectionTimeout.setIdleTimeout(timeout);
            if (LOG.isDebugEnabled()) LOG.debug("Session timer(ms)={}", timeout);
        }
    }


    public void stopTimeout ()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            if (_inspectionTimeout != null)
            {
                _inspectionTimeout.setIdleTimeout(-1);
                _inspectionTimeout = null;
                if (LOG.isDebugEnabled()) LOG.debug("Session timer stopped");
            }
        }
    }

    /** 
     * @see javax.servlet.http.HttpSession#getMaxInactiveInterval()
     */
    @Override
    public int getMaxInactiveInterval()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return (int)(_sessionData.getMaxInactiveMs()/1000);
        }
    }

    /** 
     * @see javax.servlet.http.HttpSession#getSessionContext()
     */
    @Override
    public HttpSessionContext getSessionContext()
    {
        checkValidForRead();
        return SessionManager.__nullSessionContext;
    }

    
    public SessionManager getSessionManager()
    {
        return _manager;
    }
    
    
    /* ------------------------------------------------------------- */
    /**
     * asserts that the session is valid
     * @throws IllegalStateException if the session is invalid
     */
    protected void checkValidForWrite() throws IllegalStateException
    {    
        checkLocked();

        if (_state != State.VALID)
            throw new IllegalStateException("Not valid for write: id="+_sessionData.getId()+" created="+_sessionData.getCreated()+" accessed="+_sessionData.getAccessed()+" lastaccessed="+_sessionData.getLastAccessed()+" maxInactiveMs="+_sessionData.getMaxInactiveMs()+" expiry="+_sessionData.getExpiry());
        
        if (_passivationState == PassivationState.PASSIVATED)
            throw new IllegalStateException("Not valid for write: id="+_sessionData.getId()+" passivated");
    }
    
    
    /* ------------------------------------------------------------- */
    /**
     * asserts that the session is valid
     * @throws IllegalStateException if the session is invalid
     */
    protected void checkValidForRead () throws IllegalStateException
    {
        checkLocked();
        
        if (_state == State.INVALID)
            throw new IllegalStateException("Invalid for read: id="+_sessionData.getId()+" created="+_sessionData.getCreated()+" accessed="+_sessionData.getAccessed()+" lastaccessed="+_sessionData.getLastAccessed()+" maxInactiveMs="+_sessionData.getMaxInactiveMs()+" expiry="+_sessionData.getExpiry());
        
        if (_passivationState == PassivationState.PASSIVATED)
            throw new IllegalStateException("Invalid for read: id="+_sessionData.getId()+" passivated");
    }
    
    
    /* ------------------------------------------------------------- */
    protected void checkLocked ()
    throws IllegalStateException
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
        try (Lock lock = _lock.lockIfNotHeld())
        {
            checkValidForRead();
            return _sessionData.getAttribute(name);
        }
    }

    /** 
     * @see javax.servlet.http.HttpSession#getValue(java.lang.String)
     */
    @Override
    public Object getValue(String name)
    {
        try (Lock lock = _lock.lockIfNotHeld())
        { 
            return _sessionData.getAttribute(name);
        }
    }

    /** 
     * @see javax.servlet.http.HttpSession#getAttributeNames()
     */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            checkValidForRead();
            final Iterator<String> itor = _sessionData.getKeys().iterator();
            return new Enumeration<String> ()
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




    /* ------------------------------------------------------------ */
    public int getAttributes()
    {
        return _sessionData.getKeys().size();
    }




    /* ------------------------------------------------------------ */
    public Set<String> getNames()
    {
        return Collections.unmodifiableSet(_sessionData.getKeys());
    }


    /* ------------------------------------------------------------- */
    /**
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #getAttributeNames}
     */
    @Deprecated
    @Override
    public String[] getValueNames() throws IllegalStateException
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            checkValidForRead();
            Iterator<String> itor = _sessionData.getKeys().iterator();
            if (!itor.hasNext())
                return new String[0];
            ArrayList<String> names = new ArrayList<String>();
            while (itor.hasNext())
                names.add(itor.next());
            return names.toArray(new String[names.size()]);
        }
    }

    /* ------------------------------------------------------------- */
    /** 
     * @see javax.servlet.http.HttpSession#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object old=null;
        try (Lock lock = _lock.lockIfNotHeld())
        {
            //if session is not valid, don't accept the set
            checkValidForWrite();
            old=_sessionData.setAttribute(name,value);
        }
        if (value == null && old == null)
            return; //if same as remove attribute but attribute was already removed, no change
        callSessionAttributeListeners(name, value, old);
    }
    
    
    
    /* ------------------------------------------------------------- */
    /** 
     * @see javax.servlet.http.HttpSession#putValue(java.lang.String, java.lang.Object)
     */
    @Override
    public void putValue(String name, Object value)
    {
        setAttribute(name,value);
    }
    
    
    
    /* ------------------------------------------------------------- */
    /** 
     * @see javax.servlet.http.HttpSession#removeAttribute(java.lang.String)
     */
    @Override
    public void removeAttribute(String name)
    {
       setAttribute(name, null);
    }
    
    
    
    /* ------------------------------------------------------------- */
    /** 
     * @see javax.servlet.http.HttpSession#removeValue(java.lang.String)
     */
    @Override
    public void removeValue(String name)
    {
       setAttribute(name, null);
    }

    /* ------------------------------------------------------------ */
    public void renewId(HttpServletRequest request)
    {
        if (_manager == null)
            throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());
        
        String id = null;
        String extendedId = null;
        try (Lock lock = _lock.lockIfNotHeld())
        {
            checkValidForWrite(); //don't renew id on a session that is not valid
            id = _sessionData.getId(); //grab the values as they are now
            extendedId = getExtendedId();
        }
        
        _manager._sessionIdManager.renewSessionId(id, extendedId, request); 
        setIdChanged(true);
    }
       
    
    /* ------------------------------------------------------------- */
    /* * Swap the id on a session from old to new, keeping the object
     * the same.
     * 
     * @param oldId
     * @param oldExtendedId
     * @param newId
     * @param newExtendedId
     */
   /* public void renewId (String oldId, String oldExtendedId, String newId, String newExtendedId)
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            checkValidForWrite(); //can't change id on invalid session
            
            if (!oldId.equals(getId()))
                throw new IllegalStateException("Id clash detected on renewal: was "+oldId+" but is "+ getId());
            
            _sessionData.setId(newId);
            setExtendedId(newExtendedId);
            _sessionData.setLastSaved(0); //forces an insert
            _sessionData.setDirty(true);  //forces an insert
        }
    }*/

    /* ------------------------------------------------------------- */
    /** Called by users to invalidate a session, or called by the
     * access method as a request enters the session if the session
     * has expired, or called by manager as a result of scavenger
     * expiring session
     * 
     * @see javax.servlet.http.HttpSession#invalidate()
     */
    @Override
    public void invalidate()
    {
        if (_manager == null)
            throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());

        boolean result = false;

        try (Lock lock = _lock.lockIfNotHeld())
        {
            switch (_state)
            {
                case INVALID:
                {
                    throw new IllegalStateException(); //spec does not allow invalidate of already invalid session
                }
                case VALID:
                {
                    //only first change from valid to invalidating should be actionable
                    result = true;
                    _state = State.INVALIDATING;
                    break;
                }
                default:
                {
                    LOG.info("Session {} already being invalidated", _sessionData.getId());
                }
            }
        }

        try
        {
            //if the session was not already invalid, or in process of being invalidated, do invalidate
            if (result)
            {
                //tell id mgr to remove session from all other contexts
                ((DefaultSessionIdManager)_manager.getSessionIdManager()).invalidateAll(_sessionData.getId());
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /* ------------------------------------------------------------- */
    /** Grab the lock on the session
     * @return the lock
     */
    public Lock lock ()
    {
        return _lock.lock();
    }

    /* ------------------------------------------------------------- */
    protected void doInvalidate() throws IllegalStateException
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("invalidate {}",_sessionData.getId());
                if (isValid())
                {
                    Set<String> keys = null;

                    do
                    {
                        keys = _sessionData.getKeys();
                        for (String key:keys)
                        {
                            Object  old=_sessionData.setAttribute(key,null);
                            if (old == null)
                                return; //if same as remove attribute but attribute was already removed, no change
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
            }
        }
    }

    /* ------------------------------------------------------------- */
    @Override
    public boolean isNew() throws IllegalStateException
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            checkValidForRead();
            return _newSession;
        }
    }
    
    

    /* ------------------------------------------------------------- */
    public void setIdChanged(boolean changed)
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            _idChanged=changed;
        }
    }
    
    
    /* ------------------------------------------------------------- */
    public boolean isIdChanged ()
    {
        try (Lock lock = _lock.lockIfNotHeld())
        {
            return _idChanged;
        }
    }
    
    
    /* ------------------------------------------------------------- */
    /** 
     * @see org.eclipse.jetty.server.session.SessionManager.SessionIf#getSession()
     */
    @Override
    public Session getSession()
    {
        // TODO why is this used
        return this;
    }
  
    /* ------------------------------------------------------------- */
    protected SessionData getSessionData()
    {
        return _sessionData;
    }




    
    /**
     * 
     */
    public void setPassivated ()
    {
        checkLocked();
        _passivationState = PassivationState.PASSIVATED;
    }

    /**
     * 
     */
    public void setActive ()
    {
        checkLocked();
        _passivationState = PassivationState.ACTIVE;
    }

    public boolean isActive ()
    {
        checkLocked();
        return _passivationState == PassivationState.ACTIVE;
    }

    public boolean isPassivated ()
    {
        checkLocked();
        return _passivationState == PassivationState.PASSIVATED;
    }
}

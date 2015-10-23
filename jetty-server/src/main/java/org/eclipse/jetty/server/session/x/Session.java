//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session.x;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;




/**
 * Session
 *
 *
 */
public class Session implements SessionManager.SessionIf
{

    final static Logger LOG = Log.getLogger(Session.class); // TODO SessionHandler.LOG;
    public final static String SESSION_CREATED_SECURE="org.eclipse.jetty.security.sessionCreatedSecure";
    
    protected SessionData _sessionData;
    protected SessionManager _manager;
    protected String _extendedId; //the _id plus the worker name
    protected long _requests;
    private boolean _idChanged;
    private boolean _newSession;
    private boolean _doInvalidate; //remember we should invalidate this session
    
    public Session (HttpServletRequest request, SessionData data)
    {
        _sessionData = data;
        _newSession = true;
        _requests = 1;
    }
    
    
    public Session (SessionData data)
    {
        _sessionData = data;
        _requests = 1;
    }
    
    
    public void setSessionManager (SessionManager manager)
    {
        _manager = manager;
    }
    
    
    public void setExtendedId (String extendedId)
    {
        _extendedId = extendedId;
    }
    
    /* ------------------------------------------------------------- */
    protected void cookieSet()
    {
        synchronized (this)
        {
           _sessionData.setCookieSet(_sessionData.getAccessed());
        }
    }
    /* ------------------------------------------------------------ */
    protected boolean access(long time)
    {
        synchronized(this)
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
        synchronized(this)
        {
            _requests--;
            if (_doInvalidate && _requests<=0  )
                doInvalidate();
        }
    }


    /* ------------------------------------------------------------- */
    /** 
     * 
     * @throws Exception
     */
    protected void invalidateAndRemove() throws Exception
    {
        if (_manager == null)
            throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());
        
        _manager.removeSession(this,true);

        // Notify listeners and unbind values
        boolean do_invalidate=false;
        synchronized (this)
        {
            if (!_sessionData.isInvalid())
            {
                if (_requests<=0)
                    do_invalidate=true;
                else
                    _doInvalidate=true;
            }
        }
        if (do_invalidate)
            doInvalidate();
    }
    
    
    /* ------------------------------------------------------------- */
    /** Check to see if session has expired as at the time given.
     * @param time the time in milliseconds
     * @return true if expired
     */
    protected boolean isExpiredAt(long time)
    {
        return _sessionData.isExpiredAt(time);
    }
    
    
    public void setLastNode (String nodename)
    {
        _sessionData.setLastNode(nodename);
    }
    
    
    public String getLastNode ()
    {
        return _sessionData.getLastNode();
    }

    public void clearAttributes()
    {
        Set<String> keys = null;

        do
        {
            keys = _sessionData.getKeys();
            for (String key:keys)
            {
                setAttribute(key,null);
            }

        }
        while (!keys.isEmpty());
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
    public void didActivate()
    {
        synchronized(this)
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
    }
    
    
    /* ------------------------------------------------------------- */
    public void willPassivate()
    {
        synchronized(this)
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
    }
    
    /* ------------------------------------------------------------ */
    public boolean isValid()
    {
        return !_sessionData.isInvalid();
    }


    /* ------------------------------------------------------------- */
    public long getCookieSetTime()
    {
        return _sessionData.getCookieSet();
    }
    
    /* ------------------------------------------------------------- */
    public void setCookieSetTime(long time)
    {
        _sessionData.setCookieSet(time);
    }

    /* ------------------------------------------------------------- */
    @Override
    public long getCreationTime() throws IllegalStateException
    {
        checkValid();
        return _sessionData.getCreated();
    }
    
   

    /** 
     * @see javax.servlet.http.HttpSession#getId()
     */
    @Override
    public String getId()
    {
        return _sessionData.getId();
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
        return _sessionData.getLastAccessed();
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
       _sessionData.setMaxInactiveMs((long)secs*1000L);        
    }

    /** 
     * @see javax.servlet.http.HttpSession#getMaxInactiveInterval()
     */
    @Override
    public int getMaxInactiveInterval()
    {
        return (int)(_sessionData.getMaxInactiveMs()/1000);
    }

    /** 
     * @see javax.servlet.http.HttpSession#getSessionContext()
     */
    @Override
    public HttpSessionContext getSessionContext()
    {
        checkValid();
        return SessionManager.__nullSessionContext;
    }

    /* ------------------------------------------------------------- */
    /**
     * asserts that the session is valid
     * @throws IllegalStateException if the sesion is invalid
     */
    protected void checkValid() throws IllegalStateException
    {
        if (_sessionData.isInvalid())
            throw new IllegalStateException();
    }
    
    
    /** 
     * @see javax.servlet.http.HttpSession#getAttribute(java.lang.String)
     */
    @Override
    public Object getAttribute(String name)
    {
        //TODO synchronization
        checkValid();
        return _sessionData.getAttribute(name);
    }

    /** 
     * @see javax.servlet.http.HttpSession#getValue(java.lang.String)
     */
    @Override
    public Object getValue(String name)
    {
        // TODO synchronization
        return _sessionData.getAttribute(name);
    }

    /** 
     * @see javax.servlet.http.HttpSession#getAttributeNames()
     */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        synchronized (this)
        {
            checkValid();
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
        return _sessionData.getKeys();
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
        synchronized(this)
        {
            checkValid();
            Iterator<String> itor = _sessionData.getKeys().iterator();
            if (!itor.hasNext())
                return new String[0];
            ArrayList<String> names = new ArrayList<String>();
            while (itor.hasNext())
                names.add(itor.next());
            return names.toArray(new String[names.size()]);
        }
    }


    /** 
     * @see javax.servlet.http.HttpSession#setAttribute(java.lang.String, java.lang.Object)
     */
    @Override
    public void setAttribute(String name, Object value)
    {
        Object old=null;
        synchronized (this)
        {
            //if session is not valid, don't accept the set
            checkValid();
            old=_sessionData.setAttribute(name,value);
        }
        if (value == null && old == null)
            return; //if same as remove attribute but attribute was already removed, no change
        callSessionAttributeListeners(name, value, old);
    }

    /** 
     * @see javax.servlet.http.HttpSession#putValue(java.lang.String, java.lang.Object)
     */
    @Override
    public void putValue(String name, Object value)
    {
        setAttribute(name,value);
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
    public void removeValue(String name)
    {
       setAttribute(name, null);
    }

    /* ------------------------------------------------------------ */
    public void renewId(HttpServletRequest request)
    {
        if (_manager == null)
            throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());
        
        _manager._sessionIdManager.renewSessionId(_sessionData.getId(), getExtendedId(), request); 
        setIdChanged(true);
    }
       

    /* ------------------------------------------------------------- */
    /** Called by users to invalidate a session, or called by the
     * access method as a request enters the session if the session
     * has expired.
     * 
     * @see javax.servlet.http.HttpSession#invalidate()
     */
    @Override
    public void invalidate()
    {
        if (_manager == null)
            throw new IllegalStateException ("No session manager for session "+ _sessionData.getId());
        
        checkValid();
        try
        {
            
      /*      // remove session from context 
            _manager.removeSession(this,true);
            
            //invalidate session
            doInvalidate();
            */
            //tell id mgr to remove session from all other contexts
           ((AbstractSessionIdManager)_manager.getSessionIdManager()).invalidateAll(_sessionData.getId());
           
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /* ------------------------------------------------------------- */
    protected void doInvalidate() throws IllegalStateException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invalidate {}",_sessionData.getId());
            if (isValid())
                clearAttributes();
        }
        finally
        {
            synchronized (this)
            {
                // mark as invalid
                _sessionData.setInvalid(true);
            }
        }
    }
   
    /* ------------------------------------------------------------- */
    @Override
    public boolean isNew() throws IllegalStateException
    {
        checkValid();
        return _newSession;
    }
    
    /* ------------------------------------------------------------- */
    public void setIdChanged(boolean changed)
    {
        _idChanged=changed;
    }
    
    public boolean isIdChanged ()
    {
        return _idChanged;
    }
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager.SessionIf#getSession()
     */
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
}

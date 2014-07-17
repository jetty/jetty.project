//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.util.log.Logger;

/**
 *
 * <p>
 * Implements {@link javax.servlet.http.HttpSession} from the <code>javax.servlet</code> package.
 * </p>
 *
 */
@SuppressWarnings("deprecation")
public abstract class AbstractSession implements AbstractSessionManager.SessionIf
{
    final static Logger LOG = SessionHandler.LOG;
    public final static String SESSION_KNOWN_ONLY_TO_AUTHENTICATED="org.eclipse.jetty.security.sessionKnownOnlytoAuthenticated";
    private  String _clusterId; // ID without any node (ie "worker") id appended
    private  String _nodeId;    // ID of session with node(ie "worker") id appended
    private final AbstractSessionManager _manager;
    private boolean _idChanged;
    private final long _created;
    private long _cookieSet;
    private long _accessed;         // the time of the last access
    private long _lastAccessed;     // the time of the last access excluding this one
    private boolean _invalid;
    private boolean _doInvalidate;
    private long _maxIdleMs;
    private boolean _newSession;
    private int _requests;



    /* ------------------------------------------------------------- */
    protected AbstractSession(AbstractSessionManager abstractSessionManager, HttpServletRequest request)
    {
        _manager = abstractSessionManager;

        _newSession=true;
        _created=System.currentTimeMillis();
        _clusterId=_manager._sessionIdManager.newSessionId(request,_created);
        _nodeId=_manager._sessionIdManager.getNodeId(_clusterId,request);
        _accessed=_created;
        _lastAccessed=_created;
        _requests=1;
        _maxIdleMs=_manager._dftMaxIdleSecs>0?_manager._dftMaxIdleSecs*1000L:-1;
        if (LOG.isDebugEnabled())
            LOG.debug("new session & id "+_nodeId+" "+_clusterId);
    }

    /* ------------------------------------------------------------- */
    protected AbstractSession(AbstractSessionManager abstractSessionManager, long created, long accessed, String clusterId)
    {
        _manager = abstractSessionManager;
        _created=created;
        _clusterId=clusterId;
        _nodeId=_manager._sessionIdManager.getNodeId(_clusterId,null);
        _accessed=accessed;
        _lastAccessed=accessed;
        _requests=1;
        _maxIdleMs=_manager._dftMaxIdleSecs>0?_manager._dftMaxIdleSecs*1000L:-1;
        if (LOG.isDebugEnabled())
            LOG.debug("new session "+_nodeId+" "+_clusterId);
    }

    /* ------------------------------------------------------------- */
    /**
     * asserts that the session is valid
     */
    protected void checkValid() throws IllegalStateException
    {
        if (_invalid)
            throw new IllegalStateException();
    }
    
    /* ------------------------------------------------------------- */
    /** Check to see if session has expired as at the time given.
     * @param time
     * @return
     */
    protected boolean checkExpiry(long time)
    {
        if (_maxIdleMs>0 && _lastAccessed>0 && _lastAccessed + _maxIdleMs < time)
            return true;
        return false;
    }

    /* ------------------------------------------------------------- */
    @Override
    public AbstractSession getSession()
    {
        return this;
    }

    /* ------------------------------------------------------------- */
    public long getAccessed()
    {
        synchronized (this)
        {
            return _accessed;
        }
    }

    /* ------------------------------------------------------------- */
    public abstract Map<String,Object> getAttributeMap();


 
    

    /* ------------------------------------------------------------ */
    public abstract int getAttributes();
  

 

    /* ------------------------------------------------------------ */
    public abstract Set<String> getNames();
  

    /* ------------------------------------------------------------- */
    public long getCookieSetTime()
    {
        return _cookieSet;
    }

    /* ------------------------------------------------------------- */
    @Override
    public long getCreationTime() throws IllegalStateException
    {
        checkValid();
        return _created;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getId() throws IllegalStateException
    {
        return _manager._nodeIdInSessionId?_nodeId:_clusterId;
    }

    /* ------------------------------------------------------------- */
    public String getNodeId()
    {
        return _nodeId;
    }

    /* ------------------------------------------------------------- */
    public String getClusterId()
    {
        return _clusterId;
    }

    /* ------------------------------------------------------------- */
    @Override
    public long getLastAccessedTime() throws IllegalStateException
    {
        checkValid();
        return _lastAccessed;
    }
    
    /* ------------------------------------------------------------- */
    public void setLastAccessedTime(long time)
    {
        _lastAccessed = time;
    }

    /* ------------------------------------------------------------- */
    @Override
    public int getMaxInactiveInterval()
    {
        return (int)(_maxIdleMs/1000);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see javax.servlet.http.HttpSession#getServletContext()
     */
    @Override
    public ServletContext getServletContext()
    {
        return _manager._context;
    }

    /* ------------------------------------------------------------- */
    @Deprecated
    @Override
    public HttpSessionContext getSessionContext() throws IllegalStateException
    {
        checkValid();
        return AbstractSessionManager.__nullSessionContext;
    }

    /* ------------------------------------------------------------- */
    /**
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #getAttribute}
     */
    @Deprecated
    @Override
    public Object getValue(String name) throws IllegalStateException
    {
        return getAttribute(name);
    }

 

    /* ------------------------------------------------------------ */
    public void renewId(HttpServletRequest request)
    {
        _manager._sessionIdManager.renewSessionId(getClusterId(), getNodeId(), request); 
        setIdChanged(true);
    }
       
    /* ------------------------------------------------------------- */
    public SessionManager getSessionManager()
    {
        return _manager;
    }

    /* ------------------------------------------------------------ */
    protected void setClusterId (String clusterId)
    {
        _clusterId = clusterId;
    }
    
    /* ------------------------------------------------------------ */
    protected void setNodeId (String nodeId)
    {
        _nodeId = nodeId;
    }
    

    /* ------------------------------------------------------------ */
    protected boolean access(long time)
    {
        synchronized(this)
        {
            if (_invalid)
                return false;
            _newSession=false;
            _lastAccessed=_accessed;
            _accessed=time;

            if (checkExpiry(time))
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
    protected void timeout() throws IllegalStateException
    {
        // remove session from context and invalidate other sessions with same ID.
        _manager.removeSession(this,true);

        // Notify listeners and unbind values
        boolean do_invalidate=false;
        synchronized (this)
        {
            if (!_invalid)
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
    @Override
    public void invalidate() throws IllegalStateException
    {
        checkValid();
        // remove session from context and invalidate other sessions with same ID.
        _manager.removeSession(this,true);
        doInvalidate();
    }

    /* ------------------------------------------------------------- */
    protected void doInvalidate() throws IllegalStateException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invalidate {}",_clusterId);
            if (isValid())
                clearAttributes();
        }
        finally
        {
            synchronized (this)
            {
                // mark as invalid
                _invalid=true;
            }
        }
    }

    /* ------------------------------------------------------------- */
    public abstract void clearAttributes();
   

    /* ------------------------------------------------------------- */
    public boolean isIdChanged()
    {
        return _idChanged;
    }

    /* ------------------------------------------------------------- */
    @Override
    public boolean isNew() throws IllegalStateException
    {
        checkValid();
        return _newSession;
    }

    /* ------------------------------------------------------------- */
    /**
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #setAttribute}
     */
    @Deprecated
    @Override
    public void putValue(java.lang.String name, java.lang.Object value) throws IllegalStateException
    {
        changeAttribute(name,value);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void removeAttribute(String name)
    {
        setAttribute(name,null);
    }

    /* ------------------------------------------------------------- */
    /**
     * @deprecated As of Version 2.2, this method is replaced by
     *             {@link #removeAttribute}
     */
    @Deprecated
    @Override
    public void removeValue(java.lang.String name) throws IllegalStateException
    {
        removeAttribute(name);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Enumeration<String> getAttributeNames()
    {
        synchronized (this)
        {
            checkValid();
            return doGetAttributeNames();
        }
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
            Enumeration<String> anames = doGetAttributeNames();
            if (anames == null)
                return new String[0];
            ArrayList<String> names = new ArrayList<String>();
            while (anames.hasMoreElements())
                names.add(anames.nextElement());
            return names.toArray(new String[names.size()]);
        }
    }
    

    /* ------------------------------------------------------------ */
    public abstract Object doPutOrRemove(String name, Object value);
 

    /* ------------------------------------------------------------ */
    public abstract Object doGet(String name);
    
    
    /* ------------------------------------------------------------ */
    public abstract Enumeration<String> doGetAttributeNames();
    
    
    /* ------------------------------------------------------------ */
    @Override
    public Object getAttribute(String name)
    {
        synchronized (this)
        {
            checkValid();
            return doGet(name);
        }
    }
   

    /* ------------------------------------------------------------ */
    @Override
    public void setAttribute(String name, Object value)
    {
        changeAttribute(name,value);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     * @deprecated use changeAttribute(String,Object) instead
     * @return
     */
    protected boolean updateAttribute (String name, Object value)
    {
        Object old=null;
        synchronized (this)
        {
            checkValid();
            old=doPutOrRemove(name,value);
        }

        if (value==null || !value.equals(old))
        {
            if (old!=null)
                unbindValue(name,old);
            if (value!=null)
                bindValue(name,value);

            _manager.doSessionAttributeListeners(this,name,old,value);
            return true;
        }
        return false;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Either set (perhaps replace) or remove the value of the attribute
     * in the session. The appropriate session attribute listeners are
     * also called.
     * 
     * @param name
     * @param value
     * @return
     */
    protected Object changeAttribute (String name, Object value)
    {
        Object old=null;
        synchronized (this)
        {
            checkValid();
            old=doPutOrRemove(name,value);
        }

        callSessionAttributeListeners(name, value, old);

        return old;
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

            _manager.doSessionAttributeListeners(this,name,oldValue,newValue);
        }
    }
  

    /* ------------------------------------------------------------- */
    public void setIdChanged(boolean changed)
    {
        _idChanged=changed;
    }

    /* ------------------------------------------------------------- */
    @Override
    public void setMaxInactiveInterval(int secs)
    {
        _maxIdleMs=(long)secs*1000L;
    }

    /* ------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return this.getClass().getName()+":"+getId()+"@"+hashCode();
    }

    /* ------------------------------------------------------------- */
    /** If value implements HttpSessionBindingListener, call valueBound() */
    public void bindValue(java.lang.String name, Object value)
    {
        if (value!=null&&value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(this,name));
    }

    /* ------------------------------------------------------------ */
    public boolean isValid()
    {
        return !_invalid;
    }

    /* ------------------------------------------------------------- */
    protected void cookieSet()
    {
        synchronized (this)
        {
            _cookieSet=_accessed;
        }
    }

    /* ------------------------------------------------------------ */
    public int getRequests()
    {
        synchronized (this)
        {
            return _requests;
        }
    }

    /* ------------------------------------------------------------ */
    public void setRequests(int requests)
    {
        synchronized (this)
        {
            _requests=requests;
        }
    }

    /* ------------------------------------------------------------- */
    /** If value implements HttpSessionBindingListener, call valueUnbound() */
    public void unbindValue(java.lang.String name, Object value)
    {
        if (value!=null&&value instanceof HttpSessionBindingListener)
            ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(this,name));
    }

    /* ------------------------------------------------------------- */
    public void willPassivate()
    {
        synchronized(this)
        {
            HttpSessionEvent event = new HttpSessionEvent(this);
            for (Iterator<Object> iter = getAttributeMap().values().iterator(); iter.hasNext();)
            {
                Object value = iter.next();
                if (value instanceof HttpSessionActivationListener)
                {
                    HttpSessionActivationListener listener = (HttpSessionActivationListener) value;
                    listener.sessionWillPassivate(event);
                }
            }
        }
    }

    /* ------------------------------------------------------------- */
    public void didActivate()
    {
        synchronized(this)
        {
            HttpSessionEvent event = new HttpSessionEvent(this);
            for (Iterator<Object> iter = getAttributeMap().values().iterator(); iter.hasNext();)
            {
                Object value = iter.next();
                if (value instanceof HttpSessionActivationListener)
                {
                    HttpSessionActivationListener listener = (HttpSessionActivationListener) value;
                    listener.sessionDidActivate(event);
                }
            }
        }
    }


}

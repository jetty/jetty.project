// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.session;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/* ------------------------------------------------------------ */
/**
 * An Abstract implementation of SessionManager. The partial implementation of
 * SessionManager interface provides the majority of the handling required to
 * implement a SessionManager. Concrete implementations of SessionManager based
 * on AbstractSessionManager need only implement the newSession method to return
 * a specialized version of the Session inner class that provides an attribute
 * Map.
 * <p>
 * If the property
 * org.eclipse.jetty.servlet.AbstractSessionManager.23Notifications is set to
 * true, the 2.3 servlet spec notification style will be used.
 * <p>
 *
 * 
 */
public abstract class AbstractSessionManager extends AbstractLifeCycle implements SessionManager
{
    /* ------------------------------------------------------------ */
    public final static int __distantFuture=60*60*24*7*52*20;

    private static final HttpSessionContext __nullSessionContext=new NullSessionContext();

    private boolean _usingCookies=true;

    /* ------------------------------------------------------------ */
    // Setting of max inactive interval for new sessions
    // -1 means no timeout
    protected int _dftMaxIdleSecs=-1;
    protected SessionHandler _sessionHandler;
    protected boolean _httpOnly=false;
    protected int _maxSessions=0;

    protected int _minSessions=0;
    protected SessionIdManager _sessionIdManager;
    protected boolean _secureCookies=false;
    protected Object _sessionAttributeListeners;
    protected Object _sessionListeners;

    protected ClassLoader _loader;
    protected ContextHandler.Context _context;
    protected String _sessionCookie=__DefaultSessionCookie;
    protected String _sessionIdPathParameterName = __DefaultSessionIdPathParameterName;
    protected String _sessionIdPathParameterNamePrefix =";"+ _sessionIdPathParameterName +"=";
    protected String _sessionDomain;
    protected String _sessionPath;
    protected int _maxCookieAge=-1;
    protected int _refreshCookieAge;
    protected boolean _nodeIdInSessionId;

    /* ------------------------------------------------------------ */
    public AbstractSessionManager()
    {
    }

    /* ------------------------------------------------------------ */
    public HttpCookie access(HttpSession session,boolean secure)
    {
        long now=System.currentTimeMillis();

        Session s = ((SessionIf)session).getSession();
        s.access(now);

        // Do we need to refresh the cookie?
        if (isUsingCookies() &&
            (s.isIdChanged() ||
             (getMaxCookieAge()>0 && getRefreshCookieAge()>0 && ((now-s.getCookieSetTime())/1000>getRefreshCookieAge()))
            )
           )
        {
            HttpCookie cookie=getSessionCookie(session,_context.getContextPath(),secure);
            s.cookieSet();
            s.setIdChanged(false);
            return cookie;
        }

        return null;
    }

    /* ------------------------------------------------------------ */
    public void addEventListener(EventListener listener)
    {
        if (listener instanceof HttpSessionAttributeListener)
            _sessionAttributeListeners=LazyList.add(_sessionAttributeListeners,listener);
        if (listener instanceof HttpSessionListener)
            _sessionListeners=LazyList.add(_sessionListeners,listener);
    }

    /* ------------------------------------------------------------ */
    public void clearEventListeners()
    {
        _sessionAttributeListeners=null;
        _sessionListeners=null;
    }

    /* ------------------------------------------------------------ */
    public void complete(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        s.complete();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doStart() throws Exception
    {
        _context=ContextHandler.getCurrentContext();
        _loader=Thread.currentThread().getContextClassLoader();

        if (_sessionIdManager==null)
        {
            final Server server=getSessionHandler().getServer();
            synchronized (server)
            {
                _sessionIdManager=server.getSessionIdManager();
                if (_sessionIdManager==null)
                {
                    _sessionIdManager=new HashSessionIdManager();
                    server.setSessionIdManager(_sessionIdManager);
                }
            }
        }
        if (!_sessionIdManager.isStarted())
            _sessionIdManager.start();

        // Look for a session cookie name
        String tmp=_context.getInitParameter(SessionManager.__SessionCookieProperty);
        if (tmp!=null)
            _sessionCookie=tmp;

        tmp=_context.getInitParameter(SessionManager.__SessionIdPathParameterNameProperty);
        if (tmp!=null)
        {
            setSessionIdPathParameterName(tmp);
        }

        // set up the max session cookie age if it isn't already
        if (_maxCookieAge==-1)
        {
            if (_context!=null)
            {
                String str=_context.getInitParameter(SessionManager.__MaxAgeProperty);
                if (str!=null)
                    _maxCookieAge=Integer.parseInt(str.trim());
            }
        }
        // set up the session domain if it isn't already
        if (_sessionDomain==null)
        {
            // only try the context initParams
            if (_context!=null)
                _sessionDomain=_context.getInitParameter(SessionManager.__SessionDomainProperty);
        }

        // set up the sessionPath if it isn't already
        if (_sessionPath==null)
        {
            // only the context initParams
            if (_context!=null)
                _sessionPath=_context.getInitParameter(SessionManager.__SessionPathProperty);
        }

        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doStop() throws Exception
    {
        super.doStop();

        invalidateSessions();

        _loader=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the httpOnly.
     */
    public boolean getHttpOnly()
    {
        return _httpOnly;
    }

    /* ------------------------------------------------------------ */
    public HttpSession getHttpSession(String nodeId)
    {
        String cluster_id = getIdManager().getClusterId(nodeId);

        synchronized (this)
        {
            Session session = getSession(cluster_id);

            if (session!=null && !session.getNodeId().equals(nodeId))
                session.setIdChanged(true);
            return session;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the metaManager used for cross context session management
     */
    public SessionIdManager getIdManager()
    {
        return _sessionIdManager;
    }

    /* ------------------------------------------------------------ */
    public int getMaxCookieAge()
    {
        return _maxCookieAge;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return seconds
     */
    public int getMaxInactiveInterval()
    {
        return _dftMaxIdleSecs;
    }

    /* ------------------------------------------------------------ */
    public int getMaxSessions()
    {
        return _maxSessions;
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated use {@link #getIdManager()}
     */
    @Deprecated
    public SessionIdManager getMetaManager()
    {
        return getIdManager();
    }

    /* ------------------------------------------------------------ */
    public int getMinSessions()
    {
        return _minSessions;
    }

    /* ------------------------------------------------------------ */
    public int getRefreshCookieAge()
    {
        return _refreshCookieAge;
    }


    /* ------------------------------------------------------------ */
    /**
     * @return Returns the secureCookies.
     */
    public boolean getSecureCookies()
    {
        return _secureCookies;
    }

    /* ------------------------------------------------------------ */
    public String getSessionCookie()
    {
        return _sessionCookie;
    }

    /* ------------------------------------------------------------ */
    public HttpCookie getSessionCookie(HttpSession session, String contextPath, boolean requestIsSecure)
    {
        if (isUsingCookies())
        {
            String id = getNodeId(session);
            HttpCookie cookie=new HttpCookie(
                    _sessionCookie,
                    id,
                    _sessionDomain,
                    (contextPath==null||contextPath.length()==0)?"/":contextPath,
                    getMaxCookieAge(),
                    getHttpOnly(),
                    requestIsSecure&&getSecureCookies());      
                    
            return cookie;
        }
        return null;
    }

    public String getSessionDomain()
    {
        return _sessionDomain;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the sessionHandler.
     */
    public SessionHandler getSessionHandler()
    {
        return _sessionHandler;
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated.  Need to review if it is needed.
     */
    public abstract Map getSessionMap();

    /* ------------------------------------------------------------ */
    public String getSessionPath()
    {
        return _sessionPath;
    }

    /* ------------------------------------------------------------ */
    public abstract int getSessions();

    /* ------------------------------------------------------------ */
    public String getSessionIdPathParameterName()
    {
        return _sessionIdPathParameterName;
    }

    /* ------------------------------------------------------------ */
    public String getSessionIdPathParameterNamePrefix()
    {
        return _sessionIdPathParameterNamePrefix;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the usingCookies.
     */
    public boolean isUsingCookies()
    {
        return _usingCookies;
    }

    /* ------------------------------------------------------------ */
    public boolean isValid(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.isValid();
    }

    /* ------------------------------------------------------------ */
    public String getClusterId(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.getClusterId();
    }

    /* ------------------------------------------------------------ */
    public String getNodeId(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.getNodeId();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new HttpSession for a request
     */
    public HttpSession newHttpSession(HttpServletRequest request)
    {
        Session session=newSession(request);
        session.setMaxInactiveInterval(_dftMaxIdleSecs);
        addSession(session,true);
        return session;
    }

    /* ------------------------------------------------------------ */
    public void removeEventListener(EventListener listener)
    {
        if (listener instanceof HttpSessionAttributeListener)
            _sessionAttributeListeners=LazyList.remove(_sessionAttributeListeners,listener);
        if (listener instanceof HttpSessionListener)
            _sessionListeners=LazyList.remove(_sessionListeners,listener);
    }

    /* ------------------------------------------------------------ */
    public void resetStats()
    {
        _minSessions=getSessions();
        _maxSessions=getSessions();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param httpOnly
     *            The httpOnly to set.
     */
    public void setHttpOnly(boolean httpOnly)
    {
        _httpOnly=httpOnly;
    }


    /* ------------------------------------------------------------ */
    /**
     * @param metaManager The metaManager used for cross context session management.
     */
    public void setIdManager(SessionIdManager metaManager)
    {
        _sessionIdManager=metaManager;
    }

    /* ------------------------------------------------------------ */
    public void setMaxCookieAge(int maxCookieAgeInSeconds)
    {
        _maxCookieAge=maxCookieAgeInSeconds;

        if (_maxCookieAge>0 && _refreshCookieAge==0)
            _refreshCookieAge=_maxCookieAge/3;

    }

    /* ------------------------------------------------------------ */
    /**
     * @param seconds
     */
    public void setMaxInactiveInterval(int seconds)
    {
        _dftMaxIdleSecs=seconds;
    }

    /* ------------------------------------------------------------ */
    /**
     * @deprecated use {@link #setIdManager(SessionIdManager)}
     */
    @Deprecated
    public void setMetaManager(SessionIdManager metaManager)
    {
        setIdManager(metaManager);
    }

    /* ------------------------------------------------------------ */
    public void setRefreshCookieAge(int ageInSeconds)
    {
        _refreshCookieAge=ageInSeconds;
    }


    /* ------------------------------------------------------------ */
    /**
     * @param secureCookies
     *            The secureCookies to set.
     */
    public void setSecureCookies(boolean secureCookies)
    {
        _secureCookies=secureCookies;
    }

    public void setSessionCookie(String cookieName)
    {
        _sessionCookie=cookieName;
    }

    public void setSessionDomain(String domain)
    {
        _sessionDomain=domain;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sessionHandler
     *            The sessionHandler to set.
     */
    public void setSessionHandler(SessionHandler sessionHandler)
    {
        _sessionHandler=sessionHandler;
    }

    /* ------------------------------------------------------------ */
    public void setSessionPath(String path)
    {
        _sessionPath=path;
    }

    /* ------------------------------------------------------------ */
    public void setSessionIdPathParameterName(String param)
    {
        _sessionIdPathParameterName =(param==null||"none".equals(param))?null:param;
        _sessionIdPathParameterNamePrefix =(param==null||"none".equals(param))?null:(";"+ _sessionIdPathParameterName +"=");
    }
    /* ------------------------------------------------------------ */
    /**
     * @param usingCookies
     *            The usingCookies to set.
     */
    public void setUsingCookies(boolean usingCookies)
    {
        _usingCookies=usingCookies;
    }


    protected abstract void addSession(Session session);

    /* ------------------------------------------------------------ */
    /**
     * Add the session Registers the session with this manager and registers the
     * session ID with the sessionIDManager;
     */
    protected void addSession(Session session, boolean created)
    {
        //noinspection SynchronizeOnNonFinalField
        synchronized (_sessionIdManager)
        {
            _sessionIdManager.addSession(session);
            synchronized (this)
            {
                addSession(session);
                if (getSessions()>this._maxSessions)
                    this._maxSessions=getSessions();
            }
        }

        if (!created)
        {
            session.didActivate();
        }
        else if (_sessionListeners!=null)
        {
            HttpSessionEvent event=new HttpSessionEvent(session);
            for (int i=0; i<LazyList.size(_sessionListeners); i++)
                ((HttpSessionListener)LazyList.get(_sessionListeners,i)).sessionCreated(event);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get a known existingsession
     * @param idInCluster The session ID in the cluster, stripped of any worker name.
     * @return A Session or null if none exists.
     */
    public abstract Session getSession(String idInCluster);

    protected abstract void invalidateSessions();


    /* ------------------------------------------------------------ */
    /**
     * Create a new session instance
     * @param request
     * @return
     */
    protected abstract Session newSession(HttpServletRequest request);



    /* ------------------------------------------------------------ */
    /**
     * @return true if the cluster node id (worker id) is returned as part of the session id by {@link HttpSession#getId()}. Default is false.
     */
    public boolean isNodeIdInSessionId()
    {
        return _nodeIdInSessionId;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param nodeIdInSessionId true if the cluster node id (worker id) will be returned as part of the session id by {@link HttpSession#getId()}. Default is false.
     */
    public void setNodeIdInSessionId(boolean nodeIdInSessionId)
    {
        _nodeIdInSessionId=nodeIdInSessionId;
    }

    /* ------------------------------------------------------------ */
    /** Remove session from manager
     * @param session The session to remove
     * @param invalidate True if {@link HttpSessionListener#sessionDestroyed(HttpSessionEvent)} and
     * {@link SessionIdManager#invalidateAll(String)} should be called.
     */
    public void removeSession(HttpSession session, boolean invalidate)
    {
        Session s = ((SessionIf)session).getSession();
        removeSession(s,invalidate);
    }

    /* ------------------------------------------------------------ */
    /** Remove session from manager
     * @param session The session to remove
     * @param invalidate True if {@link HttpSessionListener#sessionDestroyed(HttpSessionEvent)} and
     * {@link SessionIdManager#invalidateAll(String)} should be called.
     */
    public void removeSession(Session session, boolean invalidate)
    {
        // Remove session from context and global maps
        //noinspection SynchronizeOnNonFinalField
        synchronized (_sessionIdManager)
        {
            boolean removed = false;

            synchronized (this)
            {
                //take this session out of the map of sessions for this context
                if (getSession(session.getClusterId()) != null)
                {
                    removed = true;
                    removeSession(session.getClusterId());
                }
            }

            if (removed)
            {
                // Remove session from all context and global id maps
                _sessionIdManager.removeSession(session);
                if (invalidate)
                    _sessionIdManager.invalidateAll(session.getClusterId());
            }
        }

        if (invalidate && _sessionListeners!=null)
        {
            HttpSessionEvent event=new HttpSessionEvent(session);
            for (int i=LazyList.size(_sessionListeners); i-->0;)
                ((HttpSessionListener)LazyList.get(_sessionListeners,i)).sessionDestroyed(event);
        }
        if (!invalidate)
        {
            session.willPassivate();
        }
    }

    /* ------------------------------------------------------------ */
    protected abstract void removeSession(String idInCluster);

    /* ------------------------------------------------------------ */
    /**
     * Null returning implementation of HttpSessionContext
     *
     * 
     */
    public static class NullSessionContext implements HttpSessionContext
    {
        /* ------------------------------------------------------------ */
        private NullSessionContext()
        {
        }

        /* ------------------------------------------------------------ */
        /**
         * @deprecated From HttpSessionContext
         */
        @Deprecated
        public Enumeration getIds()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }

        /* ------------------------------------------------------------ */
        /**
         * @deprecated From HttpSessionContext
         */
        @Deprecated
        public HttpSession getSession(String id)
        {
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * Interface that any session wrapper should implement so that
     * SessionManager may access the Jetty session implementation.
     *
     */
    public interface SessionIf extends HttpSession
    {
        public Session getSession();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     *
     * <p>
     * Implements {@link javax.servlet.HttpSession} from the {@link javax.servlet} package.
     * </p>
     * 
     *
     */
    public abstract class Session implements SessionIf, Serializable
    {
        protected final String _clusterId; // ID unique within cluster
        protected final String _nodeId;    // ID unique within node
        protected boolean _idChanged;
        protected final long _created;
        protected long _cookieSet;
        protected long _accessed;
        protected long _lastAccessed;
        protected boolean _invalid;
        protected boolean _doInvalidate;
        protected long _maxIdleMs=_dftMaxIdleSecs*1000;
        protected boolean _newSession;
        protected Map _values;
        protected int _requests;

        /* ------------------------------------------------------------- */
        protected Session(HttpServletRequest request)
        {
            _newSession=true;
            _created=System.currentTimeMillis();
            _clusterId=_sessionIdManager.newSessionId(request,_created);
            _nodeId=_sessionIdManager.getNodeId(_clusterId,request);
            _accessed=_created;
            _requests=1;
        }

        /* ------------------------------------------------------------- */
        protected Session(long created, String clusterId)
        {
            _created=created;
            _clusterId=clusterId;
            _nodeId=_sessionIdManager.getNodeId(_clusterId,null);
            _accessed=_created;
        }

        /* ------------------------------------------------------------- */
        public Session getSession()
        {
            return this;
        }

        /* ------------------------------------------------------------- */
        protected void initValues()
        {
            _values=newAttributeMap();
        }

        /* ------------------------------------------------------------ */
        public Object getAttribute(String name)
        {
            synchronized (this)
            {
                if (_invalid)
                    throw new IllegalStateException();

                if (null == _values)
                    return null;

                return _values.get(name);
            }
        }

        /* ------------------------------------------------------------ */
        public Enumeration getAttributeNames()
        {
            synchronized (this)
            {
                if (_invalid)
                    throw new IllegalStateException();
                List names=_values==null?Collections.EMPTY_LIST:new ArrayList(_values.keySet());
                return Collections.enumeration(names);
            }
        }

        /* ------------------------------------------------------------- */
        public long getCookieSetTime()
        {
            return _cookieSet;
        }

        /* ------------------------------------------------------------- */
        public long getCreationTime() throws IllegalStateException
        {
            if (_invalid)
                throw new IllegalStateException();
            return _created;
        }

        /* ------------------------------------------------------------ */
        public String getId() throws IllegalStateException
        {
            return _nodeIdInSessionId?_nodeId:_clusterId;
        }

        /* ------------------------------------------------------------- */
        protected String getNodeId()
        {
            return _nodeId;
        }

        /* ------------------------------------------------------------- */
        protected String getClusterId()
        {
            return _clusterId;
        }

        /* ------------------------------------------------------------- */
        public long getLastAccessedTime() throws IllegalStateException
        {
            if (_invalid)
                throw new IllegalStateException();
            return _lastAccessed;
        }

        /* ------------------------------------------------------------- */
        public int getMaxInactiveInterval()
        {
            if (_invalid)
                throw new IllegalStateException();
            return (int)(_maxIdleMs/1000);
        }

        /* ------------------------------------------------------------ */
        /*
         * @see javax.servlet.http.HttpSession#getServletContext()
         */
        public ServletContext getServletContext()
        {
            return _context;
        }

        /* ------------------------------------------------------------- */
        /**
         * @deprecated
         */
        @Deprecated
        public HttpSessionContext getSessionContext() throws IllegalStateException
        {
            if (_invalid)
                throw new IllegalStateException();
            return __nullSessionContext;
        }

        /* ------------------------------------------------------------- */
        /**
         * @deprecated As of Version 2.2, this method is replaced by
         *             {@link #getAttribute}
         */
        @Deprecated
        public Object getValue(String name) throws IllegalStateException
        {
            return getAttribute(name);
        }

        /* ------------------------------------------------------------- */
        /**
         * @deprecated As of Version 2.2, this method is replaced by
         *             {@link #getAttributeNames}
         */
        @Deprecated
        public String[] getValueNames() throws IllegalStateException
        {
            synchronized(this)
            {
                if (_invalid)
                    throw new IllegalStateException();
                if (_values==null)
                    return new String[0];
                String[] a=new String[_values.size()];
                return (String[])_values.keySet().toArray(a);
            }
        }

        /* ------------------------------------------------------------ */
        protected void access(long time)
        {
            synchronized(this)
            {
                _newSession=false;
                _lastAccessed=_accessed;
                _accessed=time;
                _requests++;
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
            removeSession(this,true);

            // Notify listeners and unbind values
            synchronized (this)
            {
                if (_requests<=0)
                    doInvalidate();
                else
                    _doInvalidate=true;
            }
        }

        /* ------------------------------------------------------------- */
        public void invalidate() throws IllegalStateException
        {
            // remove session from context and invalidate other sessions with same ID.
            removeSession(this,true);
            doInvalidate();
        }

        /* ------------------------------------------------------------- */
        protected void doInvalidate() throws IllegalStateException
        {
            try
            {
                // Notify listeners and unbind values
                if (_invalid)
                    throw new IllegalStateException();

                while (_values!=null && _values.size()>0)
                {
                    ArrayList keys;
                    synchronized (this)
                    {
                        keys=new ArrayList(_values.keySet());
                    }

                    Iterator iter=keys.iterator();
                    while (iter.hasNext())
                    {
                        String key=(String)iter.next();

                        Object value;
                        synchronized (this)
                        {
                            value=_values.remove(key);
                        }
                        unbindValue(key,value);

                        if (_sessionAttributeListeners!=null)
                        {
                            HttpSessionBindingEvent event=new HttpSessionBindingEvent(this,key,value);

                            for (int i=0; i<LazyList.size(_sessionAttributeListeners); i++)
                                ((HttpSessionAttributeListener)LazyList.get(_sessionAttributeListeners,i)).attributeRemoved(event);
                        }
                    }
                }
            }
            finally
            {
                // mark as invalid
                _invalid=true;
            }
        }

        /* ------------------------------------------------------------- */
        public boolean isIdChanged()
        {
            return _idChanged;
        }

        /* ------------------------------------------------------------- */
        public boolean isNew() throws IllegalStateException
        {
            if (_invalid)
                throw new IllegalStateException();
            return _newSession;
        }

        /* ------------------------------------------------------------- */
        /**
         * @deprecated As of Version 2.2, this method is replaced by
         *             {@link #setAttribute}
         */
        @Deprecated
        public void putValue(java.lang.String name, java.lang.Object value) throws IllegalStateException
        {
            setAttribute(name,value);
        }

        /* ------------------------------------------------------------ */
        public void removeAttribute(String name)
        {
            Object old;
            synchronized(this)
            {
                if (_invalid)
                    throw new IllegalStateException();
                if (_values==null)
                    return;

                old=_values.remove(name);
            }

            if (old!=null)
            {
                unbindValue(name,old);
                if (_sessionAttributeListeners!=null)
                {
                    HttpSessionBindingEvent event=new HttpSessionBindingEvent(this,name,old);

                    for (int i=0; i<LazyList.size(_sessionAttributeListeners); i++)
                        ((HttpSessionAttributeListener)LazyList.get(_sessionAttributeListeners,i)).attributeRemoved(event);
                }
            }

        }

        /* ------------------------------------------------------------- */
        /**
         * @deprecated As of Version 2.2, this method is replaced by
         *             {@link #removeAttribute}
         */
        @Deprecated
        public void removeValue(java.lang.String name) throws IllegalStateException
        {
            removeAttribute(name);
        }

        /* ------------------------------------------------------------ */
        public void setAttribute(String name, Object value)
        {
            Object old_value;
            if (value==null)
            {
                removeAttribute(name);
                return;
            }

            synchronized(this)
            {
                if (_invalid)
                    throw new IllegalStateException();
                if (_values==null)
                    _values=newAttributeMap();
                old_value=_values.put(name,value);
            }

            if (old_value==null || !value.equals(old_value))
            {
                unbindValue(name,old_value);
                bindValue(name,value);

                if (_sessionAttributeListeners!=null)
                {
                    HttpSessionBindingEvent event=new HttpSessionBindingEvent(this,name,old_value==null?value:old_value);

                    for (int i=0; i<LazyList.size(_sessionAttributeListeners); i++)
                    {
                        HttpSessionAttributeListener l=(HttpSessionAttributeListener)LazyList.get(_sessionAttributeListeners,i);

                        if (old_value==null)
                            l.attributeAdded(event);
                        else
                            l.attributeReplaced(event);
                    }
                }
            }
        }

        /* ------------------------------------------------------------- */
        public void setIdChanged(boolean changed)
        {
            _idChanged=changed;
        }

        /* ------------------------------------------------------------- */
        public void setMaxInactiveInterval(int secs)
        {
            _maxIdleMs=(long)secs*1000;
        }

        /* ------------------------------------------------------------- */
        @Override
        public String toString()
        {
            return this.getClass().getName()+":"+getId()+"@"+hashCode();
        }

        /* ------------------------------------------------------------- */
        /** If value implements HttpSessionBindingListener, call valueBound() */
        protected void bindValue(java.lang.String name, Object value)
        {
            if (value!=null&&value instanceof HttpSessionBindingListener)
                ((HttpSessionBindingListener)value).valueBound(new HttpSessionBindingEvent(this,name));
        }

        /* ------------------------------------------------------------ */
        protected boolean isValid()
        {
            return !_invalid;
        }

        /* ------------------------------------------------------------ */
        protected abstract Map newAttributeMap();

        /* ------------------------------------------------------------- */
        protected void cookieSet()
        {
            _cookieSet=_accessed;
        }

        /* ------------------------------------------------------------- */
        /** If value implements HttpSessionBindingListener, call valueUnbound() */
        protected void unbindValue(java.lang.String name, Object value)
        {
            if (value!=null&&value instanceof HttpSessionBindingListener)
                ((HttpSessionBindingListener)value).valueUnbound(new HttpSessionBindingEvent(this,name));
        }

        /* ------------------------------------------------------------- */
        protected void willPassivate()
        {
            synchronized(this)
            {
                HttpSessionEvent event = new HttpSessionEvent(this);
                for (Iterator iter = _values.values().iterator(); iter.hasNext();)
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
        protected void didActivate()
        {
            synchronized(this)
            {
                HttpSessionEvent event = new HttpSessionEvent(this);
                for (Iterator iter = _values.values().iterator(); iter.hasNext();)
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
}

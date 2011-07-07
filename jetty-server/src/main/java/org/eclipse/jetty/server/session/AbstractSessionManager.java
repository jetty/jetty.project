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

import static java.lang.Math.round;

import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

/* ------------------------------------------------------------ */
/**
 * An Abstract implementation of SessionManager. The partial implementation of
 * SessionManager interface provides the majority of the handling required to
 * implement a SessionManager. Concrete implementations of SessionManager based
 * on AbstractSessionManager need only implement the newSession method to return
 * a specialised version of the Session inner class that provides an attribute
 * Map.
 * <p>
 */
@SuppressWarnings("deprecation")
public abstract class AbstractSessionManager extends AbstractLifeCycle implements SessionManager
{
    final static Logger __log = SessionHandler.__log;
    
    /* ------------------------------------------------------------ */
    public final static int __distantFuture=60*60*24*7*52*20;

    static final HttpSessionContext __nullSessionContext=new HttpSessionContext()
    {
        public HttpSession getSession(String sessionId)
        {
            return null;
        }
        
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public Enumeration getIds()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }
    };
    
    private boolean _usingCookies=true;

    /* ------------------------------------------------------------ */
    // Setting of max inactive interval for new sessions
    // -1 means no timeout
    protected int _dftMaxIdleSecs=-1;
    protected SessionHandler _sessionHandler;
    protected boolean _httpOnly=false;
    protected SessionIdManager _sessionIdManager;
    protected boolean _secureCookies=false;
    protected final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<HttpSessionAttributeListener>();
    protected final List<HttpSessionListener> _sessionListeners= new CopyOnWriteArrayList<HttpSessionListener>();

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
    protected boolean _checkingRemoteSessionIdEncoding;

    protected final CounterStatistic _sessionsStats = new CounterStatistic();
    protected final SampleStatistic _sessionTimeStats = new SampleStatistic();
    
    /* ------------------------------------------------------------ */
    public AbstractSessionManager()
    {
    }

    /* ------------------------------------------------------------ */
    public HttpCookie access(HttpSession session,boolean secure)
    {
        long now=System.currentTimeMillis();

        AbstractSession s = ((SessionIf)session).getSession();

        if (s.access(now))
        {
            // Do we need to refresh the cookie?
            if (isUsingCookies() &&
                    (s.isIdChanged() ||
                            (getMaxCookieAge()>0 && getRefreshCookieAge()>0 && ((now-s.getCookieSetTime())/1000>getRefreshCookieAge()))
                    )
            )
            {
                HttpCookie cookie=getSessionCookie(session,_context==null?"/":(_context.getContextPath()),secure);
                s.cookieSet();
                s.setIdChanged(false);
                return cookie;
            }
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    public void addEventListener(EventListener listener)
    {
        if (listener instanceof HttpSessionAttributeListener)
            _sessionAttributeListeners.add((HttpSessionAttributeListener)listener);
        if (listener instanceof HttpSessionListener)
            _sessionListeners.add((HttpSessionListener)listener);
    }

    /* ------------------------------------------------------------ */
    public void clearEventListeners()
    {
        _sessionAttributeListeners.clear();
        _sessionListeners.clear();
    }

    /* ------------------------------------------------------------ */
    public void complete(HttpSession session)
    {
        AbstractSession s = ((SessionIf)session).getSession();
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
        if (_context!=null)
        {
            String tmp=_context.getInitParameter(SessionManager.__SessionCookieProperty);
            if (tmp!=null)
                _sessionCookie=tmp;

            tmp=_context.getInitParameter(SessionManager.__SessionIdPathParameterNameProperty);
            if (tmp!=null)
                setSessionIdPathParameterName(tmp);

            // set up the max session cookie age if it isn't already
            if (_maxCookieAge==-1)
            {
                tmp=_context.getInitParameter(SessionManager.__MaxAgeProperty);
                if (tmp!=null)
                    _maxCookieAge=Integer.parseInt(tmp.trim());
            }

            // set up the session domain if it isn't already
            if (_sessionDomain==null)
                _sessionDomain=_context.getInitParameter(SessionManager.__SessionDomainProperty);

            // set up the sessionPath if it isn't already
            if (_sessionPath==null)
                _sessionPath=_context.getInitParameter(SessionManager.__SessionPathProperty);
            
            tmp=_context.getInitParameter(SessionManager.__CheckRemoteSessionEncoding);
            if (tmp!=null)
                _checkingRemoteSessionIdEncoding=Boolean.parseBoolean(tmp);
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

        AbstractSession session = getSession(cluster_id);
        if (session!=null && !session.getNodeId().equals(nodeId))
            session.setIdChanged(true);
        return session;
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
    /**
     * @see #getSessionsMax()
     */
    @Deprecated
    public int getMaxSessions()
    {
        return getSessionsMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return maximum number of sessions
     */
    public int getSessionsMax()
    {
        return (int)_sessionsStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return total number of sessions
     */
    public int getSessionsTotal()
    {
        return (int)_sessionsStats.getTotal();
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
    /**
     * @deprecated always returns 0. no replacement available.
     */
    @Deprecated
    public int getMinSessions()
    {
        return 0;
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
            String sessionPath = (_sessionPath==null) ? contextPath : _sessionPath;
            sessionPath = (sessionPath==null||sessionPath.length()==0) ? "/" : sessionPath;
            String id = getNodeId(session);
            HttpCookie cookie=new HttpCookie(
                    _sessionCookie,
                    id,
                    _sessionDomain,
                    sessionPath,
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
     * @deprecated  Need to review if it is needed.
     */
    @SuppressWarnings("rawtypes")
    public Map getSessionMap()
    {
        throw new UnsupportedOperationException();
    }

    /* ------------------------------------------------------------ */
    public String getSessionPath()
    {
        return _sessionPath;
    }

    /* ------------------------------------------------------------ */
    public int getSessions()
    {
        return (int)_sessionsStats.getCurrent();
    }

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
        AbstractSession s = ((SessionIf)session).getSession();
        return s.isValid();
    }

    /* ------------------------------------------------------------ */
    public String getClusterId(HttpSession session)
    {
        AbstractSession s = ((SessionIf)session).getSession();
        return s.getClusterId();
    }

    /* ------------------------------------------------------------ */
    public String getNodeId(HttpSession session)
    {
        AbstractSession s = ((SessionIf)session).getSession();
        return s.getNodeId();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new HttpSession for a request
     */
    public HttpSession newHttpSession(HttpServletRequest request)
    {
        AbstractSession session=newSession(request);
        session.setMaxInactiveInterval(_dftMaxIdleSecs);
        addSession(session,true);
        return session;
    }

    /* ------------------------------------------------------------ */
    public void removeEventListener(EventListener listener)
    {
        if (listener instanceof HttpSessionAttributeListener)
            _sessionAttributeListeners.remove(listener);
        if (listener instanceof HttpSessionListener)
            _sessionListeners.remove(listener);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see #statsReset()
     */
    @Deprecated
    public void resetStats()
    {
        statsReset();
    }

    /* ------------------------------------------------------------ */
    /**
     * Reset statistics values
     */
    public void statsReset()
    {
        _sessionsStats.reset(getSessions());
        _sessionTimeStats.reset();
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
     * Set if the session manager should use SecureCookies.
     * A secure cookie will only be sent by a browser on a secure (https) connection to 
     * avoid the concern of cookies being intercepted on non secure channels.
     * For the cookie to be issued as secure, the {@link ServletRequest#isSecure()} method must return true.
     * If SSL offload is used, then the {@link AbstractConnector#customize(org.eclipse.jetty.io.EndPoint, Request)}
     * method can be used to force the request to be https, or the {@link AbstractConnector#setForwarded(boolean)}
     * can be set to true, so that the X-Forwarded-Proto header is respected.
     * <p>
     * If secure session cookies are used, then a session may not be shared between http and https requests.
     * 
     * @param secureCookies If true, use secure cookies.
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
    /**
     * @see org.eclipse.jetty.server.SessionManager#setSessionPath(java.lang.String)
     */
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


    protected abstract void addSession(AbstractSession session);

    /* ------------------------------------------------------------ */
    /**
     * Add the session Registers the session with this manager and registers the
     * session ID with the sessionIDManager;
     */
    protected void addSession(AbstractSession session, boolean created)
    {
        synchronized (_sessionIdManager)
        {
            _sessionIdManager.addSession(session);
            addSession(session);
        }

        if (created)
        {
            _sessionsStats.increment();
            if (_sessionListeners!=null)
            {
                HttpSessionEvent event=new HttpSessionEvent(session);
                for (HttpSessionListener listener : _sessionListeners)
                    listener.sessionCreated(event);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get a known existing session
     * @param idInCluster The session ID in the cluster, stripped of any worker name.
     * @return A Session or null if none exists.
     */
    public abstract AbstractSession getSession(String idInCluster);

    protected abstract void invalidateSessions() throws Exception;


    /* ------------------------------------------------------------ */
    /**
     * Create a new session instance
     * @param request
     * @return the new session
     */
    protected abstract AbstractSession newSession(HttpServletRequest request);


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
        AbstractSession s = ((SessionIf)session).getSession();
        removeSession(s,invalidate);
    }

    /* ------------------------------------------------------------ */
    /** Remove session from manager
     * @param session The session to remove
     * @param invalidate True if {@link HttpSessionListener#sessionDestroyed(HttpSessionEvent)} and
     * {@link SessionIdManager#invalidateAll(String)} should be called.
     */
    public void removeSession(AbstractSession session, boolean invalidate)
    {
        // Remove session from context and global maps
        boolean removed = removeSession(session.getClusterId());
        
        if (removed)
        {
            _sessionsStats.decrement();
            _sessionTimeStats.set(round((System.currentTimeMillis() - session.getCreationTime())/1000.0));
            
            // Remove session from all context and global id maps
            _sessionIdManager.removeSession(session);
            if (invalidate)
                _sessionIdManager.invalidateAll(session.getClusterId());
            
            if (invalidate && _sessionListeners!=null)
            {
                HttpSessionEvent event=new HttpSessionEvent(session);
                for (HttpSessionListener listener : _sessionListeners)
                    listener.sessionCreated(event);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected abstract boolean removeSession(String idInCluster);
    
    /* ------------------------------------------------------------ */
    /**
     * @return maximum amount of time session remained valid
     */
    public long getSessionTimeMax()
    {
        return _sessionTimeStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return total amount of time all sessions remained valid
     */
    public long getSessionTimeTotal()
    {
        return _sessionTimeStats.getTotal();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return mean amount of time session remained valid
     */
    public double getSessionTimeMean()
    {
        return _sessionTimeStats.getMean();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return standard deviation of amount of time session remained valid
     */
    public double getSessionTimeStdDev()
    {
        return _sessionTimeStats.getStdDev();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.SessionManager#isCheckingRemoteSessionIdEncoding()
     */
    public boolean isCheckingRemoteSessionIdEncoding()
    {
        return _checkingRemoteSessionIdEncoding;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.SessionManager#setCheckingRemoteSessionIdEncoding(boolean)
     */
    public void setCheckingRemoteSessionIdEncoding(boolean remote)
    {
        _checkingRemoteSessionIdEncoding=remote;
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
        public AbstractSession getSession();
    }

    public void doSessionAttributeListeners(AbstractSession session, String name, Object old, Object value)
    {
        if (!_sessionAttributeListeners.isEmpty())
        {
            HttpSessionBindingEvent event=new HttpSessionBindingEvent(session,name,old==null?value:old);

            for (HttpSessionAttributeListener l : _sessionAttributeListeners)
            {
                if (old==null)
                    l.attributeAdded(event);
                else if (value==null)
                    l.attributeRemoved(event);
                else
                    l.attributeReplaced(event);
            }
        }
    }
}

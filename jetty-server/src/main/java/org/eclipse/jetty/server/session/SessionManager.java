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

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;
import org.eclipse.jetty.util.thread.Locker.Lock;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

import static java.lang.Math.round;



/**
 * SessionManager
 * 
 * Handles session lifecycle. There is one SessionManager per context.
 *
 */
public class SessionManager extends ContainerLifeCycle implements org.eclipse.jetty.server.SessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
            
    public Set<SessionTrackingMode> __defaultSessionTrackingModes =
        Collections.unmodifiableSet(
            new HashSet<SessionTrackingMode>(
                    Arrays.asList(new SessionTrackingMode[]{SessionTrackingMode.COOKIE,SessionTrackingMode.URL})));

    

    /* ------------------------------------------------------------ */
    public final static int __distantFuture=60*60*24*7*52*20;

    /**
     * Web.xml session-timeout is set in minutes, but is stored as an int in seconds by HttpSession and
     * the sessionmanager. Thus MAX_INT is the max number of seconds that can be set, and MAX_INT/60 is the
     * max number of minutes that you can set.
     */
    public final static java.math.BigDecimal MAX_INACTIVE_MINUTES = new java.math.BigDecimal(Integer.MAX_VALUE/60);

    static final HttpSessionContext __nullSessionContext=new HttpSessionContext()
    {
        @Override
        public HttpSession getSession(String sessionId)
        {
            return null;
        }

        @Override
        @SuppressWarnings({ "rawtypes", "unchecked" })
        public Enumeration getIds()
        {
            return Collections.enumeration(Collections.EMPTY_LIST);
        }
    };

   

    /* ------------------------------------------------------------ */
    // Setting of max inactive interval for new sessions
    // -1 means no timeout
    protected int _dftMaxIdleSecs=-1;
    protected SessionHandler _sessionHandler;
    protected boolean _httpOnly=false;
    protected SessionIdManager _sessionIdManager;
    protected boolean _secureCookies=false;
    protected boolean _secureRequestOnly=true;

    protected final List<HttpSessionAttributeListener> _sessionAttributeListeners = new CopyOnWriteArrayList<HttpSessionAttributeListener>();
    protected final List<HttpSessionListener> _sessionListeners= new CopyOnWriteArrayList<HttpSessionListener>();
    protected final List<HttpSessionIdListener> _sessionIdListeners = new CopyOnWriteArrayList<HttpSessionIdListener>();

    protected ClassLoader _loader;
    protected ContextHandler.Context _context;
    protected SessionContext _sessionContext;
    protected String _sessionCookie=__DefaultSessionCookie;
    protected String _sessionIdPathParameterName = __DefaultSessionIdPathParameterName;
    protected String _sessionIdPathParameterNamePrefix =";"+ _sessionIdPathParameterName +"=";
    protected String _sessionDomain;
    protected String _sessionPath;
    protected int _maxCookieAge=-1;
    protected int _refreshCookieAge;
    protected boolean _nodeIdInSessionId;
    protected boolean _checkingRemoteSessionIdEncoding;
    protected String _sessionComment;
    protected SessionStore _sessionStore;
    protected final SampleStatistic _sessionTimeStats = new SampleStatistic();
    protected final CounterStatistic _sessionsCreatedStats = new CounterStatistic();
    public Set<SessionTrackingMode> _sessionTrackingModes;

    private boolean _usingURLs;
    private boolean _usingCookies=true;
    
    protected ConcurrentHashSet<String> _candidateSessionIdsForExpiry = new ConcurrentHashSet<String>();

    protected Scheduler _scheduler;
    protected boolean _ownScheduler = false;
    


    
    
    /* ------------------------------------------------------------ */
    public SessionManager()
    {
        setSessionTrackingModes(__defaultSessionTrackingModes);
    }

    /* ------------------------------------------------------------ */
    public ContextHandler.Context getContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------ */
    public ContextHandler getContextHandler()
    {
        return _context.getContextHandler();
    }
    
    
    /* ------------------------------------------------------------ */
    @ManagedAttribute("path of the session cookie, or null for default")
    public String getSessionPath()
    {
        return _sessionPath;
    }
    
    
    /* ------------------------------------------------------------ */
    @ManagedAttribute("if greater the zero, the time in seconds a session cookie will last for")
    public int getMaxCookieAge()
    {
        return _maxCookieAge;
    }

    /* ------------------------------------------------------------ */
    @Override
    public HttpCookie access(HttpSession session,boolean secure)
    {
        long now=System.currentTimeMillis();

        Session s = ((SessionIf)session).getSession();

       if (s.access(now))
       {
            // Do we need to refresh the cookie?
            if (isUsingCookies() &&
                (s.isIdChanged() ||
                (getSessionCookieConfig().getMaxAge()>0 && getRefreshCookieAge()>0 && ((now-s.getCookieSetTime())/1000>getRefreshCookieAge()))
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
    @Override
    public void addEventListener(EventListener listener)
    {
        if (listener instanceof HttpSessionAttributeListener)
            _sessionAttributeListeners.add((HttpSessionAttributeListener)listener);
        if (listener instanceof HttpSessionListener)
            _sessionListeners.add((HttpSessionListener)listener);
        if (listener instanceof HttpSessionIdListener)
            _sessionIdListeners.add((HttpSessionIdListener)listener);
        addBean(listener,false);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void clearEventListeners()
    {
        for (EventListener e :getBeans(EventListener.class))
            removeBean(e);
        _sessionAttributeListeners.clear();
        _sessionListeners.clear();
        _sessionIdListeners.clear();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void complete(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
    
        try
        {
            s.complete();
            _sessionStore.put(s.getId(), s);
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doStart() throws Exception
    {
        if (_sessionStore == null)
            throw new IllegalStateException("No session store configured");
        
        
        _context=ContextHandler.getCurrentContext();
        _loader=Thread.currentThread().getContextClassLoader();

        final Server server=getSessionHandler().getServer();
        synchronized (server)
        {
            if (_sessionIdManager==null)
            {
                _sessionIdManager=server.getSessionIdManager();
                if (_sessionIdManager==null)
                {
                    //create a default SessionIdManager and set it as the shared
                    //SessionIdManager for the Server, being careful NOT to use
                    //the webapp context's classloader, otherwise if the context
                    //is stopped, the classloader is leaked.
                    ClassLoader serverLoader = server.getClass().getClassLoader();
                    try
                    {
                        Thread.currentThread().setContextClassLoader(serverLoader);
                        _sessionIdManager=new DefaultSessionIdManager(server);
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
                addBean(_sessionIdManager,false);
            }

            _scheduler = server.getBean(Scheduler.class);
            if (_scheduler == null)
            {            
                _scheduler = new ScheduledExecutorScheduler();
                _ownScheduler = true;
                _scheduler.start();

            }
        }
        

        // Look for a session cookie name
        if (_context!=null)
        {
            String tmp=_context.getInitParameter(org.eclipse.jetty.server.SessionManager.__SessionCookieProperty);
            if (tmp!=null)
                _sessionCookie=tmp;

            tmp=_context.getInitParameter(org.eclipse.jetty.server.SessionManager.__SessionIdPathParameterNameProperty);
            if (tmp!=null)
                setSessionIdPathParameterName(tmp);

            // set up the max session cookie age if it isn't already
            if (_maxCookieAge==-1)
            {
                tmp=_context.getInitParameter(org.eclipse.jetty.server.SessionManager.__MaxAgeProperty);
                if (tmp!=null)
                    _maxCookieAge=Integer.parseInt(tmp.trim());
            }

            // set up the session domain if it isn't already
            if (_sessionDomain==null)
                _sessionDomain=_context.getInitParameter(org.eclipse.jetty.server.SessionManager.__SessionDomainProperty);

            // set up the sessionPath if it isn't already
            if (_sessionPath==null)
                _sessionPath=_context.getInitParameter(org.eclipse.jetty.server.SessionManager.__SessionPathProperty);

            tmp=_context.getInitParameter(org.eclipse.jetty.server.SessionManager.__CheckRemoteSessionEncoding);
            if (tmp!=null)
                _checkingRemoteSessionIdEncoding=Boolean.parseBoolean(tmp);
        }
       
        _sessionContext = new SessionContext(_sessionIdManager.getWorkerName(), _context);       
       
       _sessionStore.initialize(_sessionContext);
       _sessionStore.start();
       
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void doStop() throws Exception
    {
        shutdownSessions();
        _sessionStore.stop();
        if (_ownScheduler && _scheduler != null)
            _scheduler.stop();
        _scheduler = null;
        super.doStop();
        _loader=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the httpOnly.
     */
    @Override
    @ManagedAttribute("true if cookies use the http only flag")
    public boolean getHttpOnly()
    {
        return _httpOnly;
    }

    /* ------------------------------------------------------------ */
    @Override
    public HttpSession getHttpSession(String extendedId)
    {
        String id = getSessionIdManager().getId(extendedId);

        Session session = getSession(id);
        if (session!=null && !session.getExtendedId().equals(extendedId))
            session.setIdChanged(true);
        return session;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the SessionIdManager used for cross context session management
     */
    @Override
    @ManagedAttribute("Session ID Manager")
    public SessionIdManager getSessionIdManager()
    {
        return _sessionIdManager;
    }


    /* ------------------------------------------------------------ */
    /**
     * @return seconds
     */
    @Override
    @ManagedAttribute("default maximum time a session may be idle for (in s)")
    public int getMaxInactiveInterval()
    {
        return _dftMaxIdleSecs;
    }



    /* ------------------------------------------------------------ */
    @ManagedAttribute("time before a session cookie is re-set (in s)")
    public int getRefreshCookieAge()
    {
        return _refreshCookieAge;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return same as SessionCookieConfig.getSecure(). If true, session
     * cookies are ALWAYS marked as secure. If false, a session cookie is
     * ONLY marked as secure if _secureRequestOnly == true and it is a HTTPS request.
     */
    @ManagedAttribute("if true, secure cookie flag is set on session cookies")
    public boolean getSecureCookies()
    {
        return _secureCookies;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if session cookie is to be marked as secure only on HTTPS requests
     */
    public boolean isSecureRequestOnly()
    {
        return _secureRequestOnly;
    }


    /* ------------------------------------------------------------ */
    /**
     * HTTPS request. Can be overridden by setting SessionCookieConfig.setSecure(true),
     * in which case the session cookie will be marked as secure on both HTTPS and HTTP.
     * @param secureRequestOnly true to set Session Cookie Config as secure
     */
    public void setSecureRequestOnly(boolean secureRequestOnly)
    {
        _secureRequestOnly = secureRequestOnly;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute("the set session cookie")
    public String getSessionCookie()
    {
        return _sessionCookie;
    }

    /* ------------------------------------------------------------ */
    /**
     * A sessioncookie is marked as secure IFF any of the following conditions are true:
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
     * @see org.eclipse.jetty.server.SessionManager#getSessionCookie(javax.servlet.http.HttpSession, java.lang.String, boolean)
     */
    @Override
    public HttpCookie getSessionCookie(HttpSession session, String contextPath, boolean requestIsSecure)
    {
        if (isUsingCookies())
        {
            String sessionPath = (_cookieConfig.getPath()==null) ? contextPath : _cookieConfig.getPath();
            sessionPath = (sessionPath==null||sessionPath.length()==0) ? "/" : sessionPath;
            String id = getExtendedId(session);
            HttpCookie cookie = null;
            if (_sessionComment == null)
            {
                cookie = new HttpCookie(
                                        _cookieConfig.getName(),
                                        id,
                                        _cookieConfig.getDomain(),
                                        sessionPath,
                                        _cookieConfig.getMaxAge(),
                                        _cookieConfig.isHttpOnly(),
                                        _cookieConfig.isSecure() || (isSecureRequestOnly() && requestIsSecure));
            }
            else
            {
                cookie = new HttpCookie(
                                        _cookieConfig.getName(),
                                        id,
                                        _cookieConfig.getDomain(),
                                        sessionPath,
                                        _cookieConfig.getMaxAge(),
                                        _cookieConfig.isHttpOnly(),
                                        _cookieConfig.isSecure() || (isSecureRequestOnly() && requestIsSecure),
                                        _sessionComment,
                                        1);
            }

            return cookie;
        }
        return null;
    }
    
    
    
    /* ------------------------------------------------------------ */
    @ManagedAttribute("domain of the session cookie, or null for the default")
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
    @ManagedAttribute("number of sessions created by this node")
    public int getSessionsCreated()
    {
        return (int) _sessionsCreatedStats.getCurrent();
    }

    /* ------------------------------------------------------------ */
    @Override
    @ManagedAttribute("name of use for URL session tracking")
    public String getSessionIdPathParameterName()
    {
        return _sessionIdPathParameterName;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getSessionIdPathParameterNamePrefix()
    {
        return _sessionIdPathParameterNamePrefix;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the usingCookies.
     */
    @Override
    public boolean isUsingCookies()
    {
        return _usingCookies;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isValid(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.isValid();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getId(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.getId();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String getExtendedId(HttpSession session)
    {
        Session s = ((SessionIf)session).getSession();
        return s.getExtendedId();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new HttpSession for a request
     */
    @Override
    public HttpSession newHttpSession(HttpServletRequest request)
    {
        long created=System.currentTimeMillis();
        String id =_sessionIdManager.newSessionId(request,created);      
        Session session = _sessionStore.newSession(request, id, created,  (_dftMaxIdleSecs>0?_dftMaxIdleSecs*1000L:-1));
        session.setExtendedId(_sessionIdManager.getExtendedId(id, request));
        session.getSessionData().setLastNode(_sessionIdManager.getWorkerName());
        session.setMaxInactiveInterval(_dftMaxIdleSecs>0?_dftMaxIdleSecs:-1); //TODO awkward: needed to kick off timer and calc expiry
        if (request.isSecure())
            session.setAttribute(Session.SESSION_CREATED_SECURE, Boolean.TRUE);
        try
        {
            _sessionStore.put(id, session);
            _sessionsCreatedStats.increment();         
            
            if (_sessionListeners!=null)
            {
                HttpSessionEvent event=new HttpSessionEvent(session);
                for (HttpSessionListener listener : _sessionListeners)
                    listener.sessionCreated(event);
            }

            return session;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return null;
        }      
    }

    /* ------------------------------------------------------------ */
    @Override
    public void removeEventListener(EventListener listener)
    {
        if (listener instanceof HttpSessionAttributeListener)
            _sessionAttributeListeners.remove(listener);
        if (listener instanceof HttpSessionListener)
            _sessionListeners.remove(listener);
        if (listener instanceof HttpSessionIdListener)
            _sessionIdListeners.remove(listener);
        removeBean(listener);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Reset statistics values
     */
    @ManagedOperation(value="reset statistics", impact="ACTION")
    public void statsReset()
    {
        _sessionsCreatedStats.reset();
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
    @Override
    public void setSessionIdManager(SessionIdManager metaManager)
    {
        updateBean(_sessionIdManager, metaManager);
        _sessionIdManager=metaManager;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxInactiveInterval(int seconds)
    {
        _dftMaxIdleSecs=seconds;
        if (LOG.isDebugEnabled())
        {
            if (_dftMaxIdleSecs <= 0)
                LOG.debug("Sessions created by this manager are immortal (default maxInactiveInterval={})",_dftMaxIdleSecs);
            else
                LOG.debug("SessionManager default maxInactiveInterval={}", _dftMaxIdleSecs);
        }
    }

    /* ------------------------------------------------------------ */
    public void setRefreshCookieAge(int ageInSeconds)
    {
        _refreshCookieAge=ageInSeconds;
    }

    /* ------------------------------------------------------------ */
    public void setSessionCookie(String cookieName)
    {
        _sessionCookie=cookieName;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sessionHandler
     *            The sessionHandler to set.
     */
    @Override
    public void setSessionHandler(SessionHandler sessionHandler)
    {
        _sessionHandler=sessionHandler;
    }


    /* ------------------------------------------------------------ */
    @Override
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



    /* ------------------------------------------------------------ */
    /**
     * Get a known existing session
     * @param id The session ID stripped of any worker name.
     * @return A Session or null if none exists.
     */
    public Session getSession(String id)
    {
        try
        {
            Session session =  _sessionStore.get(id);
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
                session.getSessionData().setLastNode(_sessionIdManager.getWorkerName());  //TODO write through the change of node?
            }
            return session;
        }
        catch (UnreadableSessionDataException e)
        {
            LOG.warn(e);
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
            LOG.warn(other);
            return null;
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Prepare sessions for session manager shutdown
     * 
     * @throws Exception if unable to shutdown sesssions
     */
    protected void shutdownSessions() throws Exception
    {
        _sessionStore.shutdown();
    }


    /* ------------------------------------------------------------ */
    /**
     * @return the session store
     */
    public SessionStore getSessionStore ()
    {
        return _sessionStore;
    }
    
    
    public void setSessionStore (SessionStore store)
    {
        _sessionStore = store;
    }
    
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
    /** 
     * Remove session from manager
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
            Session session = _sessionStore.delete(id);
            if (session != null)
            {
                if (invalidate)
                {
                    if (_sessionListeners!=null)
                    {
                        HttpSessionEvent event=new HttpSessionEvent(session);      
                        for (int i = _sessionListeners.size()-1; i>=0; i--)
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
            LOG.warn(e);
            return null;
        }
    }
    
    
   

    /* ------------------------------------------------------------ */
    /**
     * @return maximum amount of time session remained valid
     */
    @ManagedAttribute("maximum amount of time sessions have remained active (in s)")
    public long getSessionTimeMax()
    {
        return _sessionTimeStats.getMax();
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes()
    {
        return __defaultSessionTrackingModes;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes()
    {
        return Collections.unmodifiableSet(_sessionTrackingModes);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes)
    {
        _sessionTrackingModes=new HashSet<SessionTrackingMode>(sessionTrackingModes);
        _usingCookies=_sessionTrackingModes.contains(SessionTrackingMode.COOKIE);
        _usingURLs=_sessionTrackingModes.contains(SessionTrackingMode.URL);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isUsingURLs()
    {
        return _usingURLs;
    }

    /* ------------------------------------------------------------ */
    @Override
    public SessionCookieConfig getSessionCookieConfig()
    {
        return _cookieConfig;
    }

    /* ------------------------------------------------------------ */
    private SessionCookieConfig _cookieConfig =
        new CookieConfig();


    /* ------------------------------------------------------------ */
    /**
     * @return total amount of time all sessions remained valid
     */
    @ManagedAttribute("total time sessions have remained valid")
    public long getSessionTimeTotal()
    {
        return _sessionTimeStats.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return mean amount of time session remained valid
     */
    @ManagedAttribute("mean time sessions remain valid (in s)")
    public double getSessionTimeMean()
    {
        return _sessionTimeStats.getMean();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return standard deviation of amount of time session remained valid
     */
    @ManagedAttribute("standard deviation a session remained valid (in s)")
    public double getSessionTimeStdDev()
    {
        return _sessionTimeStats.getStdDev();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.SessionManager#isCheckingRemoteSessionIdEncoding()
     */
    @Override
    @ManagedAttribute("check remote session id encoding")
    public boolean isCheckingRemoteSessionIdEncoding()
    {
        return _checkingRemoteSessionIdEncoding;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.SessionManager#setCheckingRemoteSessionIdEncoding(boolean)
     */
    @Override
    public void setCheckingRemoteSessionIdEncoding(boolean remote)
    {
        _checkingRemoteSessionIdEncoding=remote;
    }


    /* ------------------------------------------------------------ */
    /**
     * Change the session id and tell the HttpSessionIdListeners the id changed.
     * 
     */
    @Override
    public void renewSessionId(String oldId, String oldExtendedId, String newId, String newExtendedId)
    {
        try
        {
            Session session = _sessionStore.renewSessionId (oldId, newId); //swap the id over
            if (session == null)
            {
                //session doesn't exist on this context
                return;
            }
            
            session.setExtendedId(newExtendedId); //remember the extended id

            //inform the listeners
            if (!_sessionIdListeners.isEmpty())
            {
                HttpSessionEvent event = new HttpSessionEvent(session);
                for (HttpSessionIdListener l:_sessionIdListeners)
                {
                    l.sessionIdChanged(event, oldId);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }
    
   
    
    /* ------------------------------------------------------------ */
    /**
     * Called either when a session has expired, or the app has
     * invalidated it.
     * 
     * @param id the id to invalidate
     */
    public void invalidate (String id)
    {
        if (StringUtil.isBlank(id))
            return;

        try
        {
            //remove the session and call the destroy listeners
            Session session = removeSession(id, true);

            if (session != null)
            {
                _sessionTimeStats.set(round((System.currentTimeMillis() - session.getSessionData().getCreated())/1000.0));
                session.doInvalidate();   
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }


    
    public void scavenge ()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;
        
        if (LOG.isDebugEnabled()) LOG.debug("Scavenging sessions");
        //Get a snapshot of the candidates as they are now. Others that
        //arrive during this processing will be dealt with on 
        //subsequent call to scavenge
        String[] ss = _candidateSessionIdsForExpiry.toArray(new String[0]);
        Set<String> candidates = new HashSet<String>(Arrays.asList(ss));
        _candidateSessionIdsForExpiry.removeAll(candidates);
        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging session ids {}", candidates);
        try
        {
            candidates = _sessionStore.checkExpiration(candidates);
            for (String id:candidates)
            {  
                try
                {
                    getSessionIdManager().expireAll(id);
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }
    
    
    public void inspect (Session session)
    {
        if (session == null)
            return;
        

        //check if the session is:
        //1. valid
        //2. expired
        //3. passivatable
        boolean passivate = false;
        try (Lock lock = session.lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Inspecting session {}, valid={}", session.getId(), session.isValid());
            
            if (!session.isValid())
                return; //do nothing, session is no longer valid
            
            if (session.isExpiredAt(System.currentTimeMillis()))
            {
                _candidateSessionIdsForExpiry.add(session.getId());
                if (LOG.isDebugEnabled())LOG.debug("Session {} is candidate for expiry", session.getId());
            }
            else if (_sessionStore.getIdlePassivationTimeoutSec() > 0 && session.isIdleLongerThan(_sessionStore.getIdlePassivationTimeoutSec()))
            {
                passivate = true;
                if (LOG.isDebugEnabled())LOG.debug("Session {} will be passivated", session.getId());
            }
        }
        
        if (passivate)
            _sessionStore.passivateIdleSession(session.getId()); //TODO call passivate inside lock
      
    }
    
    
    
    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.server.SessionManager#isIdInUse(java.lang.String)
     */
    @Override
    public boolean isIdInUse(String id) throws Exception
    {
        //Ask the session store
        return _sessionStore.exists(id);
    }
    
    
    
    
    /* ------------------------------------------------------------ */
    public Scheduler getScheduler()
    {
       return _scheduler;
    }




    /** 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
        return (_context==null?super.toString():_context.toString());
    }









    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /**
     * CookieConfig
     * 
     * Implementation of the javax.servlet.SessionCookieConfig.
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
            _sessionDomain=domain;
        }

        @Override
        public void setHttpOnly(boolean httpOnly)
        {   
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _httpOnly=httpOnly;
        }

        @Override
        public void setMaxAge(int maxAge)
        {               
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _maxCookieAge=maxAge;
        }

        @Override
        public void setName(String name)
        {  
                if (_context != null && _context.getContextHandler().isAvailable())
                    throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _sessionCookie=name;
        }

        @Override
        public void setPath(String path)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started"); 
            _sessionPath=path;
        }

        @Override
        public void setSecure(boolean secure)
        {
            if (_context != null && _context.getContextHandler().isAvailable())
                throw new IllegalStateException("CookieConfig cannot be set after ServletContext is started");
            _secureCookies=secure;
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

    public void doSessionAttributeListeners(Session session, String name, Object old, Object value)
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

//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.gcloud.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.MemSession;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

import com.google.cloud.datastore.Blob;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreException;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.GqlQuery;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.Query.ResultType;
import com.google.cloud.datastore.QueryResults;



/**
 * GCloudSessionManager
 * 
 * 
 */
public class GCloudSessionManager extends AbstractSessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    
    public static final String KIND = "GCloudSession";
    public static final int DEFAULT_MAX_QUERY_RESULTS = 100;
    public static final long DEFAULT_SCAVENGE_SEC = 600; 
    public static final int DEFAULT_BACKOFF_MS = 1000; //start at 1 sec
    public static final int DEFAULT_MAX_RETRIES = 5;
    
    /**
     * Sessions known to this node held in memory
     */
    private ConcurrentHashMap<String, GCloudSessionManager.Session> _sessions;

    
    /**
     * The length of time a session can be in memory without being checked against
     * the cluster. A value of 0 indicates that the session is never checked against
     * the cluster - the current node is considered to be the master for the session.
     *
     */
    private long _staleIntervalSec = 0;
    
    protected Scheduler.Task _task; //scavenge task
    protected Scheduler _scheduler;
    protected Scavenger _scavenger;
    protected long _scavengeIntervalMs = 1000L * DEFAULT_SCAVENGE_SEC; //10mins
    protected boolean _ownScheduler;
    
    private Datastore _datastore;
    private KeyFactory _keyFactory;


    private SessionEntityConverter _converter;


    private int _maxResults = DEFAULT_MAX_QUERY_RESULTS;
    private int _backoffMs = DEFAULT_BACKOFF_MS;
    private int _maxRetries = DEFAULT_MAX_RETRIES;


    private boolean _dsSet;


    /**
     * Scavenger
     *
     */
    protected class Scavenger implements Runnable
    {

        @Override
        public void run()
        {
           try
           {
               scavenge();
           }
           finally
           {
               if (_scheduler != null && _scheduler.isRunning())
                   _task = _scheduler.schedule(this, _scavengeIntervalMs, TimeUnit.MILLISECONDS);
           }
        }
    }

    /**
     * SessionEntityConverter
     *
     *
     */
    public class SessionEntityConverter
    {
        public  final String CLUSTERID = "clusterId";
        public  final String CONTEXTPATH = "contextPath";
        public  final String VHOST = "vhost";
        public  final String ACCESSED = "accessed";
        public  final String LASTACCESSED = "lastAccessed";
        public  final String CREATETIME = "createTime";
        public  final  String COOKIESETTIME = "cookieSetTime";
        public  final String LASTNODE = "lastNode";
        public  final String EXPIRY = "expiry";
        public  final  String MAXINACTIVE = "maxInactive";
        public  final  String ATTRIBUTES = "attributes";

      
        
        public Entity entityFromSession (Session session, Key key) throws Exception
        {
            if (session == null)
                return null;
            
            Entity entity = null;
            
            //serialize the attribute map
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(session.getAttributeMap());
            oos.flush();
            
            //turn a session into an entity
            entity = Entity.builder(key)
                    .set(CLUSTERID, session.getId())
                    .set(CONTEXTPATH, session.getContextPath())
                    .set(VHOST, session.getVHost())
                    .set(ACCESSED, session.getAccessed())
                    .set(LASTACCESSED, session.getLastAccessedTime())
                    .set(CREATETIME, session.getCreationTime())
                    .set(COOKIESETTIME, session.getCookieSetTime())
                    .set(LASTNODE,session.getLastNode())
                    .set(EXPIRY, session.getExpiry())
                    .set(MAXINACTIVE, session.getMaxInactiveInterval())
                    .set(ATTRIBUTES, Blob.copyFrom(baos.toByteArray())).build();
                     
            return entity;
        }
        
        public Session sessionFromEntity (Entity entity) throws Exception
        {
            if (entity == null)
                return null;

            final AtomicReference<Session> reference = new AtomicReference<Session>();
            final AtomicReference<Exception> exception = new AtomicReference<Exception>();
            Runnable load = new Runnable()
            {
                public void run ()
                {
                    try
                    {
                        //turn an entity into a Session
                        String clusterId = entity.getString(CLUSTERID);
                        String contextPath = entity.getString(CONTEXTPATH);
                        String vhost = entity.getString(VHOST);
                        long accessed = entity.getLong(ACCESSED);
                        long lastAccessed = entity.getLong(LASTACCESSED);
                        long createTime = entity.getLong(CREATETIME);
                        long cookieSetTime = entity.getLong(COOKIESETTIME);
                        String lastNode = entity.getString(LASTNODE);
                        long expiry = entity.getLong(EXPIRY);
                        long maxInactive = entity.getLong(MAXINACTIVE);
                        Blob blob = (Blob) entity.getBlob(ATTRIBUTES);

                        Session session = new Session (clusterId, createTime, accessed, maxInactive);
                        session.setLastNode(lastNode);
                        session.setContextPath(contextPath);
                        session.setVHost(vhost);
                        session.setCookieSetTime(cookieSetTime);
                        session.setLastAccessedTime(lastAccessed);
                        session.setLastNode(lastNode);
                        session.setExpiry(expiry);
                        try (ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(blob.asInputStream()))
                        {
                            Object o = ois.readObject();
                            session.addAttributes((Map<String,Object>)o);
                        }
                        reference.set(session);
                    }
                    catch (Exception e)
                    {
                        exception.set(e);
                    }
                }
            };
            
            if (_context==null)
                load.run();
            else
                _context.getContextHandler().handle(null,load);
   
           
            if (exception.get() != null)
            {
                exception.get().printStackTrace();
                throw exception.get();
            }
            
            return reference.get();
        }
    }
    
   
    

    
    /**
     * Session
     *
     * Representation of a session in local memory.
     */
    public class Session extends MemSession
    {
        
        private ReentrantLock _lock = new ReentrantLock();
        
        /**
         * The (canonical) context path for with which this session is associated
         */
        private String _contextPath;
        
        
        
        /**
         * The time in msec since the epoch at which this session should expire
         */
        private long _expiryTime; 
        
        
        /**
         * Time in msec since the epoch at which this session was last read from cluster
         */
        private long _lastSyncTime;
        
        
        /**
         * The workername of last node known to be managing the session
         */
        private String _lastNode;
        
        
        /**
         * If dirty, session needs to be (re)sent to cluster
         */
        protected boolean _dirty=false;
        
        
     

        /**
         * Any virtual hosts for the context with which this session is associated
         */
        private String _vhost;

        
        /**
         * Count of how many threads are active in this session
         */
        private AtomicInteger _activeThreads = new AtomicInteger(0);
        
        
        
        
        /**
         * A new session.
         * 
         * @param request
         */
        protected Session (HttpServletRequest request)
        {
            super(GCloudSessionManager.this,request);
            long maxInterval = getMaxInactiveInterval();
            _expiryTime = (maxInterval <= 0 ? 0 : (System.currentTimeMillis() + maxInterval*1000L));
            _lastNode = getSessionIdManager().getWorkerName();
           setVHost(GCloudSessionManager.getVirtualHost(_context));
           setContextPath(GCloudSessionManager.getContextPath(_context));
           _activeThreads.incrementAndGet(); //access will not be called on a freshly created session so increment here
        }
        
        
    
        
        /**
         * A restored session.
         * 
         * @param sessionId
         * @param created
         * @param accessed
         * @param maxInterval
         */
        protected Session (String sessionId, long created, long accessed, long maxInterval)
        {
            super(GCloudSessionManager.this, created, accessed, sessionId);
            _expiryTime = (maxInterval <= 0 ? 0 : (System.currentTimeMillis() + maxInterval*1000L));
        }
        
        /** 
         * Called on entry to the session.
         * 
         * @see org.eclipse.jetty.server.session.AbstractSession#access(long)
         */
        @Override
        protected boolean access(long time)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Access session({}) for context {} on worker {}", getId(), getContextPath(), getSessionIdManager().getWorkerName());
            try
            {

                long now = System.currentTimeMillis();
                //lock so that no other thread can call access or complete until the first one has refreshed the session object if necessary
                _lock.lock();
                //a request thread is entering
                if (_activeThreads.incrementAndGet() == 1)
                {
                    //if the first thread, check that the session in memory is not stale, if we're checking for stale sessions
                    if (getStaleIntervalSec() > 0  && (now - getLastSyncTime()) >= (getStaleIntervalSec() * 1000L))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Acess session({}) for context {} on worker {} stale session. Reloading.", getId(), getContextPath(), getSessionIdManager().getWorkerName());
                        refresh();
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
            finally
            {            
                _lock.unlock();
            }

            if (super.access(time))
            {
                int maxInterval=getMaxInactiveInterval();
                _expiryTime = (maxInterval <= 0 ? 0 : (time + maxInterval*1000L));
                return true;
            }
            return false;
        }


        /**
         * Exit from session
         * @see org.eclipse.jetty.server.session.AbstractSession#complete()
         */
        @Override
        protected void complete()
        {
            super.complete();

            //lock so that no other thread that might be calling access can proceed until this complete is done
            _lock.lock();

            try
            {
                //if this is the last request thread to be in the session
                if (_activeThreads.decrementAndGet() == 0)
                {
                    try
                    {
                        //an invalid session will already have been removed from the
                        //local session map and deleted from the cluster. If its valid save
                        //it to the cluster.
                        //TODO consider doing only periodic saves if only the last access
                        //time to the session changes
                        if (isValid())
                        {
                            //if session still valid && its dirty or stale or never been synced, write it to the cluster
                            //otherwise, we just keep the updated last access time in memory
                            if (_dirty || getLastSyncTime() == 0 || isStale(System.currentTimeMillis()))
                            {
                                willPassivate();
                                save(this);
                                didActivate();
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Problem saving session({})",getId(), e);
                    } 
                    finally
                    {
                        _dirty = false;
                    }
                }
            }
            finally
            {
                _lock.unlock();
            }
        }
        
        /** Test if the session is stale
         * @param atTime
         * @return true if the session is stale at the time given
         */
        protected boolean isStale (long atTime)
        {
            return (getStaleIntervalSec() > 0) && (atTime - getLastSyncTime() >= (getStaleIntervalSec()*1000L));
        }
        
        
        /** Test if the session is dirty
         * @return true if the dirty flag is set
         */
        protected boolean isDirty ()
        {
            return _dirty;
        }

        /** 
         * Expire the session.
         * 
         * @see org.eclipse.jetty.server.session.AbstractSession#timeout()
         */
        @Override
        protected void timeout()
        {
            if (LOG.isDebugEnabled()) LOG.debug("Timing out session {}", getId());
            super.timeout();
        }
        
      
        
        /**
         * Reload the session from the cluster. If the node that
         * last managed the session from the cluster is ourself,
         * then the session does not need refreshing.
         * NOTE: this method MUST be called with sufficient locks
         * in place to prevent 2 or more concurrent threads from
         * simultaneously updating the session.
         */
        private void refresh () 
        throws Exception
        {
            //get fresh copy from the cluster
            Session fresh = load(makeKey(getClusterId(), _context));

            //if the session no longer exists, invalidate
            if (fresh == null)
            {
                invalidate();
                return;
            }

            //cluster copy assumed to be the same as we were the last
            //node to manage it
            if (fresh.getLastNode().equals(getLastNode()))
                return;

            setLastNode(getSessionIdManager().getWorkerName());
            
            //prepare for refresh
            willPassivate();

            //if fresh has no attributes, remove them
            if (fresh.getAttributes() == 0)
                this.clearAttributes();
            else
            {
                //reconcile attributes
                for (String key:fresh.getAttributeMap().keySet())
                {
                    Object freshvalue = fresh.getAttribute(key);

                    //session does not already contain this attribute, so bind it
                    if (getAttribute(key) == null)
                    { 
                        doPutOrRemove(key,freshvalue);
                        bindValue(key,freshvalue);
                    }
                    else //session already contains this attribute, update its value
                    {
                        doPutOrRemove(key,freshvalue);
                    }

                }
                // cleanup, remove values from session, that don't exist in data anymore:
                for (String key : getNames())
                {
                    if (fresh.getAttribute(key) == null)
                    {
                        Object oldvalue = getAttribute(key);
                        doPutOrRemove(key,null);
                        unbindValue(key,oldvalue);
                    }
                }
            }
            //finish refresh
            didActivate();
        }


        public void setExpiry (long expiry)
        {
            _expiryTime = expiry;
        }
        

        public long getExpiry ()
        {
            return _expiryTime;
        }
        
        public boolean isExpiredAt (long time)
        {
            if (_expiryTime <= 0)
                return false; //never expires
            
            return  (_expiryTime <= time);
        }
        
        public void swapId (String newId, String newNodeId)
        {
            //TODO probably synchronize rather than use the access/complete lock?
            _lock.lock();
            setClusterId(newId);
            setNodeId(newNodeId);
            _lock.unlock();
        }
        
        @Override
        public void setAttribute (String name, Object value)
        {
            Object old = changeAttribute(name, value);
            if (value == null && old == null)
                return; //if same as remove attribute but attribute was already removed, no change
            
           _dirty = true;
        }
        
        
        public String getContextPath()
        {
            return _contextPath;
        }


        public void setContextPath(String contextPath)
        {
            this._contextPath = contextPath;
        }


        public String getVHost()
        {
            return _vhost;
        }


        public void setVHost(String vhost)
        {
            this._vhost = vhost;
        }
        
        public String getLastNode()
        {
            return _lastNode;
        }


        public void setLastNode(String lastNode)
        {
            _lastNode = lastNode;
        }


        public long getLastSyncTime()
        {
            return _lastSyncTime;
        }


        public void setLastSyncTime(long lastSyncTime)
        {
            _lastSyncTime = lastSyncTime;
        }

    }

    
    
    
    public void setDatastore (Datastore datastore)
    {
        _datastore = datastore;
        _dsSet = true;
    }


    
    /**
     * Start the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        if (_sessionIdManager == null)
            throw new IllegalStateException("No session id manager defined");

        if (!_dsSet)
            _datastore = DatastoreOptions.defaultInstance().service();
        _keyFactory = _datastore.newKeyFactory().kind(KIND);
        _converter = new SessionEntityConverter();       
        _sessions = new ConcurrentHashMap<String, Session>();

        //try and use a common scheduler, fallback to own
        _scheduler = getSessionHandler().getServer().getBean(Scheduler.class);
        if (_scheduler == null)
        {
            _scheduler = new ScheduledExecutorScheduler();
            _ownScheduler = true;
            _scheduler.start();
        }
        else if (!_scheduler.isStarted())
            throw new IllegalStateException("Shared scheduler not started");
 
        setScavengeIntervalSec(getScavengeIntervalSec());
        
        super.doStart();
    }


    /**
     * Stop the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStop()
     */
    @Override
    public void doStop() throws Exception
    {
        super.doStop();

        if (_task!=null)
            _task.cancel();
        _task=null;
        if (_ownScheduler && _scheduler !=null)
            _scheduler.stop();
        _scheduler = null;

        _sessions.clear();
        _sessions = null;
        
        if (!_dsSet)
            _datastore = null;
    }



    /**
     * Look for sessions in local memory that have expired.
     */
    public void scavenge ()
    {
        try
        {
            //scavenge in the database every so often
            scavengeGCloudDataStore();
        }
        catch (Exception e)
        {
            LOG.warn("Problem scavenging", e);
        }
    }

 
    
    protected void scavengeGCloudDataStore()
    throws Exception
    {
       
        //query the datastore for sessions that have expired
        long now = System.currentTimeMillis();
        
        //give a bit of leeway so we don't immediately something that has only just expired a nanosecond ago
        now = now - (_scavengeIntervalMs/2);
        
        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging for sessions expired before "+now);


        GqlQuery.Builder builder = Query.gqlQueryBuilder(ResultType.ENTITY, "select * from "+KIND+" where expiry < @1 limit "+_maxResults);
        builder.allowLiteral(true);
        builder.addBinding(now);
        Query<Entity> query = builder.build();
        QueryResults<Entity> results = _datastore.run(query);
        
        while (results.hasNext())
        {          
            Entity sessionEntity = results.next();
            scavengeSession(sessionEntity);        
        }

    }

    /**
     * Scavenge a session that has expired
     * @param e the session info from datastore
     * @throws Exception
     */
    protected void scavengeSession (Entity e)
            throws Exception
    {
        long now = System.currentTimeMillis();
        Session session = _converter.sessionFromEntity(e);
        if (session == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging session: {}",session.getId());
        //if the session isn't in memory already, put it there so we can do a normal timeout call
         Session memSession =  _sessions.putIfAbsent(session.getId(), session);
         if (memSession == null)
         {
             memSession = session;
         }

        //final check
        if (memSession.isExpiredAt(now))
        {
            if (LOG.isDebugEnabled()) LOG.debug("Session {} is definitely expired", memSession.getId());
            memSession.timeout();   
        }
    }

    public long getScavengeIntervalSec ()
    {
        return _scavengeIntervalMs/1000;
    }

    
    
    /**
     * Set the interval between runs of the scavenger. It should not be run too
     * often.
     * 
     * 
     * @param sec the number of seconds between scavenge cycles
     */
    public void setScavengeIntervalSec (long sec)
    {

        long old_period=_scavengeIntervalMs;
        long period=sec*1000L;

        _scavengeIntervalMs=period;

        if (_scavengeIntervalMs > 0)
        {
            //add a bit of variability into the scavenge time so that not all
            //nodes with the same scavenge time sync up
            long tenPercent = _scavengeIntervalMs/10;
            if ((System.currentTimeMillis()%2) == 0)
                _scavengeIntervalMs += tenPercent;
            if (LOG.isDebugEnabled())
                LOG.debug("Scavenging every "+_scavengeIntervalMs+" ms");
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Scavenging disabled"); 
        }

 
        
        synchronized (this)
        {
            if (_scheduler != null && (period!=old_period || _task==null))
            {
                //clean up any previously scheduled scavenger
                if (_task!=null)
                    _task.cancel();

                //start a new one
                if (_scavengeIntervalMs > 0)
                {
                    if (_scavenger == null)
                        _scavenger = new Scavenger();

                    _task = _scheduler.schedule(_scavenger,_scavengeIntervalMs,TimeUnit.MILLISECONDS);
                }
            }
        }
    }
    
    
    public long getStaleIntervalSec()
    {
        return _staleIntervalSec;
    }


    public void setStaleIntervalSec(long staleIntervalSec)
    {
        _staleIntervalSec = staleIntervalSec;
    }
    
    
    public int getMaxResults()
    {
        return _maxResults;
    }


    public void setMaxResults(int maxResults)
    {
        if (_maxResults <= 0)
            _maxResults = DEFAULT_MAX_QUERY_RESULTS;
        else
            _maxResults = maxResults;
    }


    /** 
     * Add a new session for the context related to this session manager
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#addSession(org.eclipse.jetty.server.session.AbstractSession)
     */
    @Override
    protected void addSession(AbstractSession session)
    {
        if (session==null)
            return;
        
        if (LOG.isDebugEnabled()) LOG.debug("Adding session({}) to session manager for context {} on worker {}",session.getClusterId(), getContextPath(getContext()),getSessionIdManager().getWorkerName() + " with lastnode="+((Session)session).getLastNode());
        _sessions.put(session.getClusterId(), (Session)session);
        
        try
        {     
                session.willPassivate();
                save(((GCloudSessionManager.Session)session));
                session.didActivate();
            
        }
        catch (Exception e)
        {
            LOG.warn("Unable to store new session id="+session.getId() , e);
        }
    }

    /** 
     * Ask the cluster for the session.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#getSession(java.lang.String)
     */
    @Override
    public AbstractSession getSession(String idInCluster)
    {
        Session session = null;

        //try and find the session in this node's memory
        Session memSession = (Session)_sessions.get(idInCluster);

        if (LOG.isDebugEnabled())
            LOG.debug("getSession({}) {} in session map",idInCluster,(memSession==null?"not":""));

        long now = System.currentTimeMillis();
        try
        {
            //if the session is not in this node's memory, then load it from the datastore
            if (memSession == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("getSession({}): loading session data from cluster", idInCluster);

                session = load(makeKey(idInCluster, _context));
                if (session != null)
                {
                    //Check that it wasn't expired
                    if (session.getExpiry() > 0 && session.getExpiry() <= now)
                    {
                        if (LOG.isDebugEnabled()) LOG.debug("getSession ({}): Session expired", idInCluster);
                        //ensure that the session id for the expired session is deleted so that a new session with the 
                        //same id cannot be created (because the idInUse() test would succeed)
                        ((GCloudSessionIdManager)getSessionIdManager()).removeSession(session);
                        return null;  
                    }

                    //Update the last worker node to me
                    session.setLastNode(getSessionIdManager().getWorkerName());                            
                    //TODO consider saving session here if lastNode was not this node

                    //Check that another thread hasn't loaded the same session
                    Session existingSession = _sessions.putIfAbsent(idInCluster, session);
                    if (existingSession != null)
                    {
                        //use the one that the other thread inserted
                        session = existingSession;
                        LOG.debug("getSession({}): using session loaded by another request thread ", idInCluster);
                    }
                    else
                    {
                        //indicate that the session was reinflated
                        session.didActivate();
                        LOG.debug("getSession({}): loaded session from cluster", idInCluster);
                    }
                    return session;
                }
                else
                {
                    //The requested session does not exist anywhere in the cluster
                    LOG.debug("getSession({}): No session in cluster matching",idInCluster);
                    return null;
                }
            }
            else
            {
               //The session exists in this node's memory
               LOG.debug("getSession({}): returning session from local memory ", memSession.getClusterId());
                return memSession;
            }
        }
        catch (Exception e)
        {
            LOG.warn("Unable to load session="+idInCluster, e);
            return null;
        }
    }
    
    

    /** 
     * The session manager is stopping.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#shutdownSessions()
     */
    @Override
    protected void shutdownSessions() throws Exception
    {
        Set<String> keys = new HashSet<String>(_sessions.keySet());
        for (String key:keys)
        {
            Session session = _sessions.remove(key); //take the session out of the session list
            //If the session is dirty, then write it to the cluster.
            //If the session is simply stale do NOT write it to the cluster, as some other node
            //may have started managing that session - this means that the last accessed/expiry time
            //will not be updated, meaning it may look like it can expire sooner than it should.
            try
            {
                if (session.isDirty())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Saving dirty session {} before exiting ", session.getId());
                    save(session);
                }
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }


    @Override
    protected AbstractSession newSession(HttpServletRequest request)
    {
        return new Session(request);
    }

    /** 
     * Remove a session from local memory, and delete it from
     * the cluster cache.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#removeSession(java.lang.String)
     */
    @Override
    protected boolean removeSession(String idInCluster)
    {
        Session session = (Session)_sessions.remove(idInCluster);
        try
        {
            if (session != null)
            {
                delete(session);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Problem deleting session id="+idInCluster, e);
        }
        return session!=null;
    }
    
    
    
    
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, String newClusterId, String newNodeId)
    {
        Session session = null;
        try
        {
            //take the session with that id out of our managed list
            session = (Session)_sessions.remove(oldClusterId);
            if (session != null)
            {
                //TODO consider transactionality and ramifications if the session is live on another node
                delete(session); //delete the old session from the cluster  
                session.swapId(newClusterId, newNodeId); //update the session
                _sessions.put(newClusterId, session); //put it into managed list under new key
                save(session); //put the session under the new id into the cluster
            }
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }

        super.renewSessionId(oldClusterId, oldNodeId, newClusterId, newNodeId);
    }


    /**
     * Load a session from the clustered cache.
     * 
     * @param key the unique datastore key for the session
     * @return the Session object restored from datastore
     */
    protected Session load (Key key)
    throws Exception
    {
        if (_datastore == null)
            throw new IllegalStateException("No DataStore");
        
        if (LOG.isDebugEnabled()) LOG.debug("Loading session {} from DataStore ", key);

        Entity entity = _datastore.get(key);
        if (entity == null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("No session {} in DataStore ",key);
            return null;
        }
        else
        {
            Session session = _converter.sessionFromEntity(entity);
            session.setLastSyncTime(System.currentTimeMillis());
            return session;
        }
    }
    
    
    
    /**
     * Save or update the session to the cluster cache
     * 
     * @param session the session to save to datastore
     * @throws Exception
     */
    protected void save (GCloudSessionManager.Session session)
    throws Exception
    {
        if (_datastore == null)
            throw new IllegalStateException("No DataStore");
        
        if (LOG.isDebugEnabled()) LOG.debug("Writing session {} to DataStore", session.getId());
    
        Entity entity = _converter.entityFromSession(session, makeKey(session, _context));
        
        //attempt the update with exponential back-off
        int backoff = getBackoffMs();
        int attempts;
        for (attempts = 0; attempts < getMaxRetries(); attempts++)
        {
            try
            {
                _datastore.put(entity);
                session.setLastSyncTime(System.currentTimeMillis());
                return;
            }
            catch (DatastoreException e)
            {
                if (e.retryable())
                {
                    if (LOG.isDebugEnabled()) LOG.debug("Datastore put retry {} waiting {}ms", attempts, backoff);
                        
                    try
                    {
                        Thread.currentThread().sleep(backoff);
                    }
                    catch (InterruptedException x)
                    {
                    }
                    backoff *= 2;
                }
                else
                {
                   throw e;
                }
            }
        }
        throw new IOException("Retries exhausted");
    }
    
    
    
    public int getMaxRetries()
    {
        return _maxRetries;
    }


    public int getBackoffMs()
    {
        return _backoffMs;
    }


    /**
     * @param backoffMs the backoffMs to set
     */
    public void setBackoffMs(int backoffMs)
    {
        _backoffMs = backoffMs;
    }


    /**
     * @param maxRetries the maxRetries to set
     */
    public void setMaxRetries(int maxRetries)
    {
        _maxRetries = maxRetries;
    }


    /**
     * Remove the session from the cluster cache.
     * 
     * @param session the session to delete from datastore
     */
    protected void delete (GCloudSessionManager.Session session)
    {  
        if (_datastore == null)
            throw new IllegalStateException("No DataStore");
        if (LOG.isDebugEnabled()) LOG.debug("Removing session {} from DataStore", session.getId());
        _datastore.delete(makeKey(session, _context));
    }

    
    /**
     * Invalidate a session for this context with the given id
     * 
     * @param idInCluster the id of the session to invalidate
     */
    public void invalidateSession (String idInCluster)
    {
        Session session = (Session)_sessions.get(idInCluster);

        if (session != null)
        {
            session.invalidate();
        }
    }

    
    /**
     * Make a unique key for this session.
     * As the same session id can be used across multiple contexts, to
     * make it unique, the key must be composed of:
     * <ol>
     * <li>the id</li>
     * <li>the context path</li>
     * <li>the virtual hosts</li>
     * </ol>
     * 
     * @param session the session for which the key should be created
     * @param context the context to which the session belongs
     * @return a unique datastore key for the session
     */
    protected Key makeKey (Session session, Context context)
    {
       return makeKey(session.getId(), context);
    }
    
    /**
     * Make a unique key for this session.
     * As the same session id can be used across multiple contexts, to
     * make it unique, the key must be composed of:
     * <ol>
     * <li>the id</li>
     * <li>the context path</li>
     * <li>the virtual hosts</li>
     * </ol>
     * 
     * @param id the id of the session for which the key should be created
     * @param context the context to which the session belongs
     * @return a unique datastore key for the session
     */
    protected Key makeKey (String id, Context context)
    {
        return _keyFactory.newKey(canonicalizeKey(id,context));
    }
    
    
    /**
     * Make a unique string from the session id and info from its Context
     * @param id the id of the Session
     * @param context the Context in which the Session exists
     * @return a unique string representing the id of the session in the context
     */
    protected String canonicalizeKey(String id, Context context)
    {
        String key = getContextPath(context);
        key = key + "_" + getVirtualHost(context);
        key = key+"_"+id;
        return key;
    }
    
    /**
     * Turn the context path into an acceptable string
     * 
     * @param context a context
     * @return a stringified version of the context
     */
    private static String getContextPath (ContextHandler.Context context)
    {
        return canonicalize (context.getContextPath());
    }

    /**
     * Get the first virtual host for the context.
     *
     * Used to help identify the exact session/contextPath.
     *
     * @param context a context
     * @return a stringified form of the virtual hosts for the context, 0.0.0.0 if none are defined
     */
    private static String getVirtualHost (ContextHandler.Context context)
    {
        String vhost = "0.0.0.0";

        if (context==null)
            return vhost;

        String [] vhosts = context.getContextHandler().getVirtualHosts();
        if (vhosts==null || vhosts.length==0 || vhosts[0]==null)
            return vhost;

        return vhosts[0];
    }

    /**
     * Make an acceptable name from a context path.
     *
     * @param path a context path
     * @return a stringified form of the context path
     */
    private static String canonicalize (String path)
    {
        if (path==null)
            return "";

        return path.replace('/', '_').replace('.','_').replace('\\','_');
    }

}

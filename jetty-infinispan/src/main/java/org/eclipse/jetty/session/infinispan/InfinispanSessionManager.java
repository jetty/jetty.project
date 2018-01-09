//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.session.infinispan;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandler.Context;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.server.session.MemSession;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.infinispan.commons.api.BasicCache;

/**
 * InfinispanSessionManager
 * 
 * The data for a session relevant to a particular context is stored in an Infinispan (clustered) cache:
 * <pre>
 * Key:   is the id of the session + the context path + the vhost for the context 
 * Value: is the data of the session
 * </pre>
 * 
 * The key is necessarily complex because the same session id can be in-use by more than one
 * context. In this case, the contents of the session will strictly be different for each
 * context, although the id will be the same.
 * 
 * Sessions are also kept in local memory when they are used by this session manager. This allows
 * multiple different request threads in the same context to call Request.getSession() and
 * obtain the same object.
 * 
 * This session manager support scavenging, which is only done over the set of sessions in its
 * local memory. This can result in some sessions being "stranded" in the cluster cache if no
 * session manager is currently managing it (eg the node managing the session crashed and it
 * was never requested on another node).
 * 
 */
public class InfinispanSessionManager extends AbstractSessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    /**
     * Clustered cache of sessions
     */
    private BasicCache<String, Object> _cache;
    
    
    /**
     * Sessions known to this node held in memory
     */
    private ConcurrentHashMap<String, InfinispanSessionManager.Session> _sessions;

    
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
    protected long _scavengeIntervalMs = 1000L * 60 * 10; //10mins
    protected boolean _ownScheduler;
    
    

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
    
    
    /*
     * Every time a Session is put into the cache one of these objects
     * is created to copy the data out of the in-memory session, and 
     * every time an object is read from the cache one of these objects
     * a fresh Session object is created based on the data held by this
     * object.
     */
    public class SerializableSessionData implements Serializable
    {
        /**
         * 
         */
        private static final long serialVersionUID = -7779120106058533486L;
        String clusterId;
        String contextPath;
        String vhost;
        long accessed;
        long lastAccessed;
        long createTime;
        long cookieSetTime;
        String lastNode;
        long expiry;
        long maxInactive;
        Map<String, Object> attributes;

        public SerializableSessionData()
        {

        }

       
       public SerializableSessionData(Session s)
       {
           clusterId = s.getClusterId();
           contextPath = s.getContextPath();
           vhost = s.getVHost();
           accessed = s.getAccessed();
           lastAccessed = s.getLastAccessedTime();
           createTime = s.getCreationTime();
           cookieSetTime = s.getCookieSetTime();
           lastNode = s.getLastNode();
           expiry = s.getExpiry();
           maxInactive = s.getMaxInactiveInterval();
           attributes = s.getAttributeMap(); // TODO pointer, not a copy
       }
        
        private void writeObject(java.io.ObjectOutputStream out) throws IOException
        {  
            out.writeUTF(clusterId); //session id
            out.writeUTF(contextPath); //context path
            out.writeUTF(vhost); //first vhost

            out.writeLong(accessed);//accessTime
            out.writeLong(lastAccessed); //lastAccessTime
            out.writeLong(createTime); //time created
            out.writeLong(cookieSetTime);//time cookie was set
            out.writeUTF(lastNode); //name of last node managing
      
            out.writeLong(expiry); 
            out.writeLong(maxInactive);
            out.writeObject(attributes);
        }
        
        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException
        {
            clusterId = in.readUTF();
            contextPath = in.readUTF();
            vhost = in.readUTF();
            
            accessed = in.readLong();//accessTime
            lastAccessed = in.readLong(); //lastAccessTime
            createTime = in.readLong(); //time created
            cookieSetTime = in.readLong();//time cookie was set
            lastNode = in.readUTF(); //last managing node
            expiry = in.readLong(); 
            maxInactive = in.readLong();
            attributes = (HashMap<String,Object>)in.readObject();
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
         * @param request the request
         */
        protected Session (HttpServletRequest request)
        {
            super(InfinispanSessionManager.this,request);
            long maxInterval = getMaxInactiveInterval();
            _expiryTime = (maxInterval <= 0 ? 0 : (System.currentTimeMillis() + maxInterval*1000L));
            _lastNode = getSessionIdManager().getWorkerName();
           setVHost(InfinispanSessionManager.getVirtualHost(_context));
           setContextPath(InfinispanSessionManager.getContextPath(_context));
           _activeThreads.incrementAndGet(); //access will not be called on a freshly created session so increment here
        }
        
        
        protected Session (SerializableSessionData sd)
        {
            super(InfinispanSessionManager.this, sd.createTime, sd.accessed, sd.clusterId);
            _expiryTime = (sd.maxInactive <= 0 ? 0 : (System.currentTimeMillis() + sd.maxInactive*1000L));
            setLastNode(sd.lastNode);
            setContextPath(sd.contextPath);
            setVHost(sd.vhost);
            addAttributes(sd.attributes);
        }
        
        
        /**
         * A restored session.
         * 
         * @param sessionId the session id
         * @param created time created
         * @param accessed time last accessed
         * @param maxInterval max expiry interval
         */
        protected Session (String sessionId, long created, long accessed, long maxInterval)
        {
            super(InfinispanSessionManager.this, created, accessed, sessionId);
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
         * @param atTime time when stale
         * @return true if stale
         */
        protected boolean isStale (long atTime)
        {
            return (getStaleIntervalSec() > 0) && (atTime - getLastSyncTime() >= (getStaleIntervalSec()*1000L));
        }
        
        
        /** Test if the session is dirty
         * @return true if dirty
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
        
        if (_cache == null)
            throw new IllegalStateException("No session cache defined");
        
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
 
        setScavengeInterval(getScavengeInterval());
        
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
    }
    
    
    
    /**
     * Look for sessions in local memory that have expired.
     */
    /**
     * 
     */
    public void scavenge ()
    {
        Set<String> candidateIds = new HashSet<String>();
        long now = System.currentTimeMillis();
        
        LOG.info("SessionManager for context {} scavenging at {} ", getContextPath(getContext()), now);
        for (Map.Entry<String, Session> entry:_sessions.entrySet())
        {
            long expiry = entry.getValue().getExpiry();
            if (expiry > 0 && expiry < now)
                candidateIds.add(entry.getKey());
        }

        for (String candidateId:candidateIds)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Session {} expired ", candidateId);
            
            Session candidateSession = _sessions.get(candidateId);
            if (candidateSession != null)
            {
                //double check the state of the session in the cache, as the
                //session may have migrated to another node. This leaves a window
                //where the cached session may have been changed by another node
                Session cachedSession = load(makeKey(candidateId, _context));
                if (cachedSession == null)
                {
                   if (LOG.isDebugEnabled()) LOG.debug("Locally expired session({}) does not exist in cluster ",candidateId);
                    //the session no longer exists, do a full invalidation
                    candidateSession.timeout();
                }
                else if (getSessionIdManager().getWorkerName().equals(cachedSession.getLastNode()))
                {
                    if (LOG.isDebugEnabled()) LOG.debug("Expiring session({}) local to session manager",candidateId);
                    //if I am the master of the session then it can be timed out
                    candidateSession.timeout();
                }
                else
                {
                    //some other node is the master of the session, simply remove it from my memory
                    if (LOG.isDebugEnabled()) LOG.debug("Session({}) not local to this session manager, removing from local memory", candidateId);
                    candidateSession.willPassivate();
                    _sessions.remove(candidateSession.getClusterId());
                }

            }
        }
    }
    
    

    public long getScavengeInterval ()
    {
        return _scavengeIntervalMs/1000;
    }

    
    
    /**
     * Set the interval between runs of the scavenger. It should not be run too
     * often.
     * 
     * 
     * @param sec scavenge interval in seconds
     */
    public void setScavengeInterval (long sec)
    {
        if (sec<=0)
            sec=60;

        long old_period=_scavengeIntervalMs;
        long period=sec*1000L;

        _scavengeIntervalMs=period;

        //add a bit of variability into the scavenge time so that not all
        //nodes with the same scavenge time sync up
        long tenPercent = _scavengeIntervalMs/10;
        if ((System.currentTimeMillis()%2) == 0)
            _scavengeIntervalMs += tenPercent;

        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging every "+_scavengeIntervalMs+" ms");
        
        synchronized (this)
        {
            if (_scheduler != null && (period!=old_period || _task==null))
            {
                if (_task!=null)
                    _task.cancel();
                if (_scavenger == null)
                    _scavenger = new Scavenger();
                
                _task = _scheduler.schedule(_scavenger,_scavengeIntervalMs,TimeUnit.MILLISECONDS);
            }
        }
    }
    
    
    

    /**
     * Get the clustered cache instance.
     * 
     * @return the cache
     */
    public BasicCache<String, Object> getCache() 
    {
        return _cache;
    }

    
    
    /**
     * Set the clustered cache instance.
     * 
     * @param cache the cache
     */
    public void setCache (BasicCache<String, Object> cache) 
    {
        this._cache = cache;
    }


    
    
    
    public long getStaleIntervalSec()
    {
        return _staleIntervalSec;
    }


    public void setStaleIntervalSec(long staleIntervalSec)
    {
        _staleIntervalSec = staleIntervalSec;
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
                save(((InfinispanSessionManager.Session)session));
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
            //if the session is not in this node's memory, then load it from the cluster cache
            if (memSession == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("getSession({}): loading session data from cluster", idInCluster);

                session = load(makeKey(idInCluster, _context));
                if (session != null)
                {
                    //We retrieved a session with the same key from the database

                    //Check that it wasn't expired
                    if (session.getExpiry() > 0 && session.getExpiry() <= now)
                    {
                        if (LOG.isDebugEnabled()) LOG.debug("getSession ({}): Session expired", idInCluster);
                        //ensure that the session id for the expired session is deleted so that a new session with the 
                        //same id cannot be created (because the idInUse() test would succeed)
                        ((InfinispanSessionIdManager)getSessionIdManager()).removeSession(session);
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
                delete(session);
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
     * @param key the session key
     * @return the session
     */
    protected Session load (String key)
    {
        if (_cache == null)
            throw new IllegalStateException("No cache");
        
        if (LOG.isDebugEnabled()) LOG.debug("Loading session {} from cluster", key);

        SerializableSessionData storableSession = (SerializableSessionData)_cache.get(key);
        if (storableSession == null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("No session {} in cluster ",key);
            return null;
        }
        else
        {
            Session session = new Session (storableSession);
            session.setLastSyncTime(System.currentTimeMillis());
            return session;
        }
    }
    
    
    
    /**
     * Save or update the session to the cluster cache
     * 
     * @param session the session
     * @throws Exception if unable to save
     */
    protected void save (InfinispanSessionManager.Session session)
    throws Exception
    {
        if (_cache == null)
            throw new IllegalStateException("No cache");
        
        if (LOG.isDebugEnabled()) LOG.debug("Writing session {} to cluster", session.getId());
    
        SerializableSessionData storableSession = new SerializableSessionData(session);

        //Put an idle timeout on the cache entry if the session is not immortal - 
        //if no requests arrive at any node before this timeout occurs, or no node 
        //scavenges the session before this timeout occurs, the session will be removed.
        //NOTE: that no session listeners can be called for this.
        InfinispanSessionIdManager sessionIdManager = (InfinispanSessionIdManager)getSessionIdManager();
        if (storableSession.maxInactive > 0)
            _cache.put(makeKey(session, _context), storableSession, -1, TimeUnit.SECONDS, storableSession.maxInactive*sessionIdManager.getIdleExpiryMultiple(), TimeUnit.SECONDS);
        else
            _cache.put(makeKey(session, _context), storableSession);
        
        //tickle the session id manager to keep the sessionid entry for this session up-to-date
        sessionIdManager.touch(session.getClusterId());
        
        session.setLastSyncTime(System.currentTimeMillis());
    }
    
    
    
    /**
     * Remove the session from the cluster cache.
     * 
     * @param session the session
     */
    protected void delete (InfinispanSessionManager.Session session)
    {  
        if (_cache == null)
            throw new IllegalStateException("No cache");
        if (LOG.isDebugEnabled()) LOG.debug("Removing session {} from cluster", session.getId());
        _cache.remove(makeKey(session, _context));
    }

    
    /**
     * Invalidate a session for this context with the given id
     * 
     * @param idInCluster session id in cluster
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
     *TODO consider the difference between getClusterId and getId
     * @param session
     * @return
     */
    private String makeKey (Session session, Context context)
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
     *TODO consider the difference between getClusterId and getId
     * @param session
     * @return
     */
    private String makeKey (String id, Context context)
    {
        String key = getContextPath(context);
        key = key + "_" + getVirtualHost(context);
        key = key+"_"+id;
        return key;
    }
    
    /**
     * Turn the context path into an acceptable string
     * 
     * @param context
     * @return
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
     * @return 0.0.0.0 if no virtual host is defined
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
     * @param path
     * @return
     */
    private static String canonicalize (String path)
    {
        if (path==null)
            return "";

        return path.replace('/', '_').replace('.','_').replace('\\','_');
    }

}

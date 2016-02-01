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

package org.eclipse.jetty.gcloud.session;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.session.AbstractSessionStore;
import org.eclipse.jetty.server.session.MemorySessionStore;
import org.eclipse.jetty.server.session.SessionManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/**
 * GCloudSessionManager
 * 
 * 
 */
public class GCloudSessionManager extends SessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

  
 


    private GCloudSessionDataStore _sessionDataStore = null;



    
/*
    
    *//**
     * Session
     *
     * Representation of a session in local memory.
     *//*
    public class Session extends MemSession
    {
        
        private ReentrantLock _lock = new ReentrantLock();
        
     
        private long _lastSyncTime;

        private AtomicInteger _activeThreads = new AtomicInteger(0);
        
        
       
        protected Session (HttpServletRequest request)
        {
           _activeThreads.incrementAndGet(); //access will not be called on a freshly created session so increment here
        }
        
        
    
        *//** 
         * Called on entry to the session.
         * 
         * @see org.eclipse.jetty.server.session.AbstractSession#access(long)
         *//*
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


        *//**
         * Exit from session
         * @see org.eclipse.jetty.server.session.AbstractSession#complete()
         *//*
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
        
        *//** Test if the session is stale
         * @param atTime
         * @return
         *//*
        protected boolean isStale (long atTime)
        {
            return (getStaleIntervalSec() > 0) && (atTime - getLastSyncTime() >= (getStaleIntervalSec()*1000L));
        }
      
        
        *//**
         * Reload the session from the cluster. If the node that
         * last managed the session from the cluster is ourself,
         * then the session does not need refreshing.
         * NOTE: this method MUST be called with sufficient locks
         * in place to prevent 2 or more concurrent threads from
         * simultaneously updating the session.
         *//*
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


        public void swapId (String newId, String newNodeId)
        {
            //TODO probably synchronize rather than use the access/complete lock?
            _lock.lock();
            setClusterId(newId);
            setNodeId(newNodeId);
            _lock.unlock();
        }


    }

*/

    
    /**
     * 
     */
    public GCloudSessionManager()
    {
        _sessionDataStore = new GCloudSessionDataStore();
        _sessionStore = new MemorySessionStore();
    }

    
    

    /**
     * @return
     */
    public GCloudSessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }





    /**
     * Start the session manager.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        ((AbstractSessionStore)_sessionStore).setSessionDataStore(_sessionDataStore);
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
    }



 
    
    protected void scavengeGCloudDataStore()
    throws Exception
    {
       
     
    }
}

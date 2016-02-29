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

package org.eclipse.jetty.session.infinispan;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.server.session.SessionManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.infinispan.commons.api.BasicCache;




/**
 * InfinispanSessionIdManager
 *
 * Maintain a set of in-use session ids. This session id manager does NOT locally store 
 * a list of in-use sesssion ids, but rather stores them in the cluster cache. Thus,
 * all operations to this session manager involve interaction with a possibly remote
 * cache.
 * 
 * For each session id that is in-use, an entry of the following form is put into 
 * the cluster cache:
 * <pre>
 *   ("__o.e.j.s.infinispanIdMgr__"+[id], [id])
 * </pre>
 * where [id] is the id of the session.
 * 
 * If the first session to be added is not immortal (ie it has a timeout on it) then
 * the corresponding session id is entered into infinispan with an idle expiry timeout
 * equivalent to double the session's timeout (the multiplier is configurable).
 * 
 * 
 * Having one entry per in-use session id means that there is no contention on
 * cache entries (as would be the case if a single entry was kept containing a 
 * list of in-use session ids).
 * 
 * 
 */
public class InfinispanSessionIdManager extends AbstractSessionIdManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    public final static String ID_KEY = "__o.e.j.s.infinispanIdMgr__";
    protected BasicCache<String,Object> _cache;
    private int _infinispanIdleTimeoutSec = 0;

    
    
    
    
    public InfinispanSessionIdManager(Server server)
    {
        super(server);
    }

    public InfinispanSessionIdManager(Server server, Random random)
    {
       super(server, random);
    }

    
    
    /** 
     * Start the id manager.
     * @see org.eclipse.jetty.server.session.AbstractSessionIdManager#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    
    
    /** 
     * Stop the id manager
     * @see org.eclipse.jetty.server.session.AbstractSessionIdManager#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    
   

    
    /** 
     * Check to see if the given session id is being
     * used by a session in any context.
     * 
     * This method will consult the cluster.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#isIdInUse(java.lang.String)
     */
    @Override
    public boolean isIdInUse(String id)
    {
        if (id == null)
            return false;
        
        String clusterId = getId(id);
        
        //ask the cluster - this should also tickle the idle expiration timer on the sessionid entry
        //keeping it valid
        try
        {
            return exists(clusterId);
        }
        catch (Exception e)
        {
            LOG.warn("Problem checking inUse for id="+clusterId, e);
            return false;
        }

    }


    public void setInfinispanIdleTimeoutSec (int sec)
    {
        if (sec <= 1)
        {
            LOG.warn("Idle expiry multiple of {} for session ids set to less than minimum. Using value of {} instead.", sec, 0);
            _infinispanIdleTimeoutSec = 0;
        }
        else
            _infinispanIdleTimeoutSec = sec;
    }

    
    
    
    public int getInfinispanIdleTimeoutSec()
    {
        return _infinispanIdleTimeoutSec;
    }
    
   


    /**
     * Get the cache.
     * @return the cache
     */
    public BasicCache<String,Object> getCache() 
    {
        return _cache;
    }

    /**
     * Set the cache.
     * @param cache the cache
     */
    public void setCache(BasicCache<String,Object> cache) 
    {
        this._cache = cache;
    }
    
    
    
    /**
     * Do any operation to the session id in the cache to
     * ensure its idle expiry time moves forward
     * @param id the session id
     */
    public void touch (String id)
    {
        exists(id);
    }
    
    
    
    /**
     * Ask the cluster if a particular id exists.
     * 
     * @param id the session id
     * @return true if exists
     */
    protected boolean exists (String id)
    {
        if (_cache == null)
            throw new IllegalStateException ("No cache");
        
        return _cache.containsKey(makeKey(id));
    }
    

    /**
     * Put a session id into the cluster.
     * 
     * @param id the session id
     */
    protected void insert (String id)
    {        
        if (_cache == null)
            throw new IllegalStateException ("No cache");
        
        _cache.putIfAbsent(makeKey(id), id);
    }
    
    
    /**
     * Put a session id into the cluster with an idle expiry.
     * 
     * @param id the session id
     * @param idleTimeOutSec idle timeout in seconds
     */
    protected void insert (String id, long idleTimeOutSec)
    {
        if (_cache == null)
            throw new IllegalStateException ("No cache");
        
        _cache.putIfAbsent(makeKey(id),id,-1L, TimeUnit.SECONDS, idleTimeOutSec, TimeUnit.SECONDS);
    }
   
    
    /**
     * Remove a session id from the cluster.
     * 
     * @param id the session id
     */
    protected boolean delete (String id)
    {
        if (_cache == null)
            throw new IllegalStateException ("No cache");
        
        return _cache.remove(makeKey(id)) != null;
    }
    
    

    /**
     * Generate a unique cache key from the session id.
     * 
     * @param id the session id
     * @return unique cache id
     */
    protected String makeKey (String id)
    {
        return ID_KEY+id;
    }

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#useId(java.lang.String)
     */
    @Override
    public void useId(Session session)
    {
        if (session == null)
            return;
        
        if (session.getMaxInactiveInterval() > 0 && getInfinispanIdleTimeoutSec() > 0)
            insert (session.getId(), getInfinispanIdleTimeoutSec());
        else
            insert (session.getId());
    }

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#removeId(java.lang.String)
     */
    @Override
    public boolean removeId(String id)
    {
       return delete (id);        
    }

   

    
}

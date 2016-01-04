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
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.SessionHandler;
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
    public static final int DEFAULT_IDLE_EXPIRY_MULTIPLE = 2;
    protected BasicCache<String,Object> _cache;
    private Server _server;
    private int _idleExpiryMultiple = DEFAULT_IDLE_EXPIRY_MULTIPLE;

    
    
    
    
    public InfinispanSessionIdManager(Server server)
    {
        super();
        _server = server;
    }

    public InfinispanSessionIdManager(Server server, Random random)
    {
       super(random);
       _server = server;
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
     * @see org.eclipse.jetty.server.SessionIdManager#idInUse(java.lang.String)
     */
    @Override
    public boolean idInUse(String id)
    {
        if (id == null)
            return false;
        
        String clusterId = getClusterId(id);
        
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

    /** 
     * Remember a new in-use session id.
     * 
     * This will save the in-use session id to the cluster.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#addSession(javax.servlet.http.HttpSession)
     */
    @Override
    public void addSession(HttpSession session)
    {
        if (session == null)
            return;

        //insert into the cache and set an idle expiry on the entry that
        //is based off the max idle time configured for the session. If the
        //session is immortal, then there is no idle expiry on the corresponding
        //session id
        if (session.getMaxInactiveInterval() == 0)
            insert (((AbstractSession)session).getClusterId());
        else
            insert (((AbstractSession)session).getClusterId(), session.getMaxInactiveInterval() * getIdleExpiryMultiple());
    }
    
    
    public void setIdleExpiryMultiple (int multiplier)
    {
        if (multiplier <= 1)
        {
            LOG.warn("Idle expiry multiple of {} for session ids set to less than minimum. Using value of {} instead.", multiplier, DEFAULT_IDLE_EXPIRY_MULTIPLE);
        }
        _idleExpiryMultiple = multiplier;
    }

    public int getIdleExpiryMultiple ()
    {
        return _idleExpiryMultiple;
    }
    
    
    /** 
     * Remove a session id from the list of in-use ids.
     * 
     * This will remvove the corresponding session id from the cluster.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#removeSession(javax.servlet.http.HttpSession)
     */
    @Override
    public void removeSession(HttpSession session)
    {
        if (session == null)
            return;

        //delete from the cache
        delete (((AbstractSession)session).getClusterId());
    }

    /** 
     * Remove a session id. This compels all other contexts who have a session
     * with the same id to also remove it.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#invalidateAll(java.lang.String)
     */
    @Override
    public void invalidateAll(String id)
    {
        //delete the session id from list of in-use sessions
        delete (id);


        //tell all contexts that may have a session object with this id to
        //get rid of them
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null)
            {
                SessionManager manager = sessionHandler.getSessionManager();

                if (manager != null && manager instanceof InfinispanSessionManager)
                {
                    ((InfinispanSessionManager)manager).invalidateSession(id);
                }
            }
        }

    }

    /** 
     * Change a session id. 
     * 
     * Typically this occurs when a previously existing session has passed through authentication.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionIdManager#renewSessionId(java.lang.String, java.lang.String, javax.servlet.http.HttpServletRequest)
     */
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());

        delete(oldClusterId);
        insert(newClusterId);


        //tell all contexts to update the id 
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null) 
            {
                SessionManager manager = sessionHandler.getSessionManager();

                if (manager != null && manager instanceof InfinispanSessionManager)
                {
                    ((InfinispanSessionManager)manager).renewSessionId(oldClusterId, oldNodeId, newClusterId, getNodeId(newClusterId, request));
                }
            }
        }

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
    protected void delete (String id)
    {
        if (_cache == null)
            throw new IllegalStateException ("No cache");
        
        _cache.remove(makeKey(id));
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
}

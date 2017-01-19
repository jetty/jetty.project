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

import java.util.Random;

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

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreFactory;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;



/**
 * GCloudSessionIdManager
 *
 * 
 * 
 */
public class GCloudSessionIdManager extends AbstractSessionIdManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    public static final int DEFAULT_IDLE_EXPIRY_MULTIPLE = 2;
    public static final String KIND = "GCloudSessionId";
    private Server _server;
    private Datastore _datastore;
    private KeyFactory _keyFactory;
    
    
    
 
    /**
     * @param server
     */
    public GCloudSessionIdManager(Server server)
    {
        super();
        _server = server;
    }

    /**
     * @param server
     * @param random
     */
    public GCloudSessionIdManager(Server server, Random random)
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
        _datastore = DatastoreOptions.defaultInstance().service();
        _keyFactory = _datastore.newKeyFactory().kind(KIND);
  
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

        //insert into the store
        insert (((AbstractSession)session).getClusterId());
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

                if (manager != null && manager instanceof GCloudSessionManager)
                {
                    ((GCloudSessionManager)manager).invalidateSession(id);
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

                if (manager != null && manager instanceof GCloudSessionManager)
                {
                    ((GCloudSessionManager)manager).renewSessionId(oldClusterId, oldNodeId, newClusterId, getNodeId(newClusterId, request));
                }
            }
        }

    }

    
    
    /**
     * Ask the datastore if a particular id exists.
     * 
     * @param id the session id to check for existence
     * @return true if a session with that id exists, false otherwise
     */
    protected boolean exists (String id)
    {
        if (_datastore == null)
            throw new IllegalStateException ("No DataStore");
        Key key = _keyFactory.newKey(id);
        return _datastore.get(key) != null;
    }
    

    /**
     * Put a session id into the cluster.
     * 
     * @param id the id to mark as in use
     */
    protected void insert (String id)
    {        
        if (_datastore == null)
            throw new IllegalStateException ("No DataStore");

        Entity entity = Entity.builder(makeKey(id))
                        .set("id", id).build();
        _datastore.put(entity);
    }

   
   
    
    /**
     * Remove a session id from the cluster.
     * 
     * @param id the id to remove
     */
    protected void delete (String id)
    {
        if (_datastore == null)
            throw new IllegalStateException ("No DataStore");
        
        _datastore.delete(makeKey(id));
    }
    
    

    /**
     * Generate a unique key from the session id.
     * 
     * @param id the id of the session
     * @return a unique key for the session id
     */
    protected Key makeKey (String id)
    {
        return _keyFactory.newKey(id);
    }
}

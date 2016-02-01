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

import java.util.Random;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.google.gcloud.datastore.Datastore;
import com.google.gcloud.datastore.DatastoreFactory;
import com.google.gcloud.datastore.Entity;
import com.google.gcloud.datastore.Key;
import com.google.gcloud.datastore.KeyFactory;



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
    private Datastore _datastore;
    private KeyFactory _keyFactory;
    private GCloudConfiguration _config;
    
    
    
 
    /**
     * @param server
     */
    public GCloudSessionIdManager(Server server)
    {
        super(server);
    }

    /**
     * @param server
     * @param random
     */
    public GCloudSessionIdManager(Server server, Random random)
    {
       super(server,random);
    }



    /** 
     * Start the id manager.
     * @see org.eclipse.jetty.server.session.AbstractSessionIdManager#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_config == null)
            throw new IllegalStateException("No gcloud configuration specified");       
        

        _datastore = DatastoreFactory.instance().get(_config.getDatastoreOptions());
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

    
 
  

    
    public GCloudConfiguration getConfig()
    {
        return _config;
    }

    public void setConfig(GCloudConfiguration config)
    {
        _config = config;
    }

   


    
    
    /**
     * Ask the datastore if a particular id exists.
     * 
     * @param id
     * @return
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
     * @param id
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
     * @param id
     */
    protected boolean delete (String id)
    {
        if (_datastore == null)
            throw new IllegalStateException ("No DataStore");
        
        _datastore.delete(makeKey(id));
        return true; //gcloud does not distinguish between first and subsequent removes
    }
    
    

    /**
     * Generate a unique key from the session id.
     * 
     * @param id
     * @return
     */
    protected Key makeKey (String id)
    {
        return _keyFactory.newKey(id);
    }

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#isIdInUse(java.lang.String)
     */
    @Override
    public boolean isIdInUse(String id)
    {
        if (id == null)
            return false;
        
        
        //ask the cluster - this should also tickle the idle expiration timer on the sessionid entry
        //keeping it valid
        try
        {
            return exists(id);
        }
        catch (Exception e)
        {
            LOG.warn("Problem checking inUse for id="+id, e);
            return false;
        }
        
    }

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#useId(org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void useId(Session session)
    {
        if (session == null)
            return;

        //insert into the store
        insert (session.getId());
    }

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#removeId(java.lang.String)
     */
    @Override
    public boolean removeId(String id)
    {
       if (id == null)
           return false;
       
       return delete(id);
    }
}

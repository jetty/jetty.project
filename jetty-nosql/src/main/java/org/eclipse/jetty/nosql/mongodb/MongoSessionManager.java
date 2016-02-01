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

package org.eclipse.jetty.nosql.mongodb;

import java.net.UnknownHostException;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.session.AbstractSessionStore;
import org.eclipse.jetty.server.session.MemorySessionStore;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionManager;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.mongodb.DBCollection;
import com.mongodb.MongoException;


/**
 * MongoSessionManager
 * <p>
 * Clustered session manager using MongoDB as the shared DB instance.
 * The document model is an outer object that contains the elements:
 * <ul>
 *  <li>"id"      : session_id </li>
 *  <li>"created" : create_time </li>
 *  <li>"accessed": last_access_time </li>
 *  <li>"maxIdle" : max_idle_time setting as session was created </li>
 *  <li>"expiry"  : time at which session should expire </li>
 *  <li>"valid"   : session_valid </li>
 *  <li>"context" : a nested object containing 1 nested object per context for which the session id is in use
 * </ul>
 * Each of the nested objects inside the "context" element contains:
 * <ul>
 *  <li>unique_context_name : nested object containing name:value pairs of the session attributes for that context</li>
 * </ul>
 * <p>
 * One of the name:value attribute pairs will always be the special attribute "__metadata__". The value 
 * is an object representing a version counter which is incremented every time the attributes change.
 * </p>
 * <p>
 * For example:
 * <pre>
 * { "_id"       : ObjectId("52845534a40b66410f228f23"), 
 *    "accessed" :  NumberLong("1384818548903"), 
 *    "maxIdle"  : 1,
 *    "context"  : { "::/contextA" : { "A"            : "A", 
 *                                     "__metadata__" : { "version" : NumberLong(2) } 
 *                                   },
 *                   "::/contextB" : { "B"            : "B", 
 *                                     "__metadata__" : { "version" : NumberLong(1) } 
 *                                   } 
 *                 }, 
 *    "created"  : NumberLong("1384818548903"),
 *    "expiry"   : NumberLong("1384818549903"),
 *    "id"       : "w01ijx2vnalgv1sqrpjwuirprp7", 
 *    "valid"    : true 
 * }
 * </pre>
 * <p>
 * In MongoDB, the nesting level is indicated by "." separators for the key name. Thus to
 * interact with a session attribute, the key is composed of:
 * <code>"context".unique_context_name.attribute_name</code>
 *  Eg  <code>"context"."::/contextA"."A"</code>
 */
@ManagedObject("Mongo Session Manager")
public class MongoSessionManager extends SessionManager
{
    private final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");


    
    /**
     * Access to MongoDB
     */
    private DBCollection _dbSessions;
    
    
    private MongoSessionDataStore _sessionDataStore;


    /* ------------------------------------------------------------ */
    public MongoSessionManager() throws UnknownHostException, MongoException
    {
        _sessionStore = new MemorySessionStore();
        _sessionDataStore = new MongoSessionDataStore();
    }
    
    
    
    /*------------------------------------------------------------ */
    @Override
    public void doStart() throws Exception
    {    
        ((AbstractSessionStore)_sessionStore).setSessionDataStore(_sessionDataStore);
        _sessionDataStore.setDBCollection(_dbSessions);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#setSessionIdManager(org.eclipse.jetty.server.SessionIdManager)
     */
    @Override
    public void setSessionIdManager(SessionIdManager metaManager)
    {
        MongoSessionIdManager msim = (MongoSessionIdManager)metaManager;
        _dbSessions=msim.getSessions();
        super.setSessionIdManager(metaManager);
        
    }

                        {
                        }
    
    public MongoSessionDataStore getSessionDataStore()
    {
        return _sessionDataStore;
    }
  

    /*------------------------------------------------------------ */
    /**
     * returns the total number of session objects in the session store
     * 
     * the count() operation itself is optimized to perform on the server side
     * and avoid loading to client side.
     * @return the session store count
     */
    @ManagedAttribute("total number of known sessions in the store")
    public long getSessionStoreCount()
    {
        return _dbSessions.find().count();      
    }

}

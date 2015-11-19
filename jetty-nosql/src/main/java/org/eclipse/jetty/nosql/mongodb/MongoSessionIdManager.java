//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.Random;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * Manager of session ids based on sessions stored in Mongo.
 * 
 */
public class MongoSessionIdManager extends AbstractSessionIdManager
{
    private final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    final static DBObject __version_1 = new BasicDBObject(MongoSessionDataStore.__VERSION,1);
    final static DBObject __valid_false = new BasicDBObject(MongoSessionDataStore.__VALID,false);
    final static DBObject __valid_true = new BasicDBObject(MongoSessionDataStore.__VALID,true);

    
    final DBCollection _sessions;
    protected Server _server;

    
    /**
     * the collection of session ids known to this manager
     */
    protected final Set<String> _sessionsIds = new ConcurrentHashSet<>();

   
    
    

    /* ------------------------------------------------------------ */
    public MongoSessionIdManager(Server server) throws UnknownHostException, MongoException
    {
        this(server, new Mongo().getDB("HttpSessions").getCollection("sessions"));
    }

    /* ------------------------------------------------------------ */
    public MongoSessionIdManager(Server server, DBCollection sessions)
    {
        super(new Random());
        
        _server = server;
        _sessions = sessions;

        _sessions.ensureIndex(
                BasicDBObjectBuilder.start().add("id",1).get(),
                BasicDBObjectBuilder.start().add("unique",true).add("sparse",false).get());
        _sessions.ensureIndex(
                BasicDBObjectBuilder.start().add("id",1).add("version",1).get(),
                BasicDBObjectBuilder.start().add("unique",true).add("sparse",false).get());

        // index our accessed and valid fields so that purges are faster, note that the "valid" field is first
        // so that we can take advantage of index prefixes
        // http://docs.mongodb.org/manual/core/index-compound/#compound-index-prefix
        _sessions.ensureIndex(
                BasicDBObjectBuilder.start().add(MongoSessionDataStore.__VALID, 1).add(MongoSessionDataStore.__ACCESSED, 1).get(),
                BasicDBObjectBuilder.start().add("sparse", false).add("background", true).get());
    }
 
 
    
    
    /* ------------------------------------------------------------ */
    public DBCollection getSessions()
    {
        return _sessions;
    }
    

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        LOG.debug("MongoSessionIdManager:starting");


        synchronized (this)
        {
          
          
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {

        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * Searches database to find if the session id known to mongo, and is it valid
     */
    @Override
    public boolean isIdInUse(String sessionId)
    {        
        /*
         * optimize this query to only return the valid variable
         */
        DBObject o = _sessions.findOne(new BasicDBObject("id",sessionId), __valid_true);
        
        if ( o != null )
        {                    
            Boolean valid = (Boolean)o.get(MongoSessionDataStore.__VALID);
            if ( valid == null )
            {
                return false;
            }            
            
            return valid;
        }
        
        return false;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void useId(Session session)
    {
        if (session == null)
        {
            return;
        }
        
        /*
         * already a part of the index in mongo...
         */
        
        LOG.debug("MongoSessionIdManager:addSession {}", session.getId());
        
        _sessionsIds.add(session.getId());
    }
 

    /* ------------------------------------------------------------ */
    @Override
    public void removeId(String id)
    {
        if (id == null)
        {
            return;
        }
        
        _sessionsIds.remove(id);
    }

    /* ------------------------------------------------------------ */
    /** Remove the session id from the list of in-use sessions.
     * Inform all other known contexts that sessions with the same id should be
     * invalidated.
     * @see org.eclipse.jetty.server.SessionIdManager#expireAll(java.lang.String)
     */
    @Override
    public void expireAll(String sessionId)
    {
       synchronized(_sessionsIds)
       {
           super.expireAll(sessionId);
       }
    }      
 

  
    
    /* ------------------------------------------------------------ */
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        synchronized (_sessionsIds)
        {
            super.renewSessionId(oldClusterId, oldNodeId, request);
        }
    }
    
}

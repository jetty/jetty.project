package org.eclipse.jetty.nosql.mongodb;
//========================================================================
//Copyright (c) 2011 Intalio, Inc.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================


import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * Based partially on the jdbc session id manager...
 *
 * Theory is that we really only need the session id manager for the local 
 * instance so we have something to scavenge on, namely the list of known ids
 * 
 * this class has a timer that runs at the scavenge delay that runs a query
 *  for all id's known to this node and that have and old accessed value greater
 *  then the scavengeDelay.
 *  
 * these found sessions are then run through the invalidateAll(id) method that 
 * is a bit hinky but is supposed to notify all handlers this id is now DOA and 
 * ought to be cleaned up.  this ought to result in a save operation on the session
 * that will change the valid field to false (this conjecture is unvalidated atm)
 */
public class MongoSessionIdManager extends AbstractSessionIdManager
{
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");

    final static DBObject __version_1 = new BasicDBObject(MongoSessionManager.__VERSION,1);
    final static DBObject __valid_false = new BasicDBObject(MongoSessionManager.__VALID,false);

    final DBCollection _sessions;
    protected Server _server;
    private Timer _scavengeTimer;
    private Timer _purgeTimer;
    private TimerTask _scavengerTask;
    private TimerTask _purgeTask;

    
    
    private long _scavengeDelay = 30 * 60 * 1000; // every 30 minutes
    private long _scavengePeriod = 10 * 6 * 1000; // wait at least 10 minutes
    

    /** 
     * purge process is enabled by default
     */
    private boolean _purge = true;

    /**
     * purge process would run daily by default
     */
    private long _purgeDelay = 24 * 60 * 60 * 1000; // every day
    
    /**
     * how long do you want to persist sessions that are no longer
     * valid before removing them completely
     */
    private long _purgeInvalidAge = 24 * 60 * 60 * 1000; // default 1 day

    /**
     * how long do you want to leave sessions that are still valid before
     * assuming they are dead and removing them
     */
    private long _purgeValidAge = 7 * 24 * 60 * 60 * 1000; // default 1 week

    
    /**
     * the collection of session ids known to this manager
     * 
     * TODO consider if this ought to be concurrent or not
     */
    protected final Set<String> _sessionsIds = new HashSet<String>();
    

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

    }
 
    /* ------------------------------------------------------------ */
    /**
     * Scavenge is a process that periodically checks the tracked session
     * ids of this given instance of the session id manager to see if they 
     * are past the point of expiration.
     */
    protected void scavenge()
    {
        __log.debug("SessionIdManager:scavenge:called with delay" + _scavengeDelay);
                
        synchronized (_sessionsIds)
        {         
            /*
             * run a query returning results that:
             *  - are in the known list of sessionIds
             *  - have an accessed time less then current time - the scavenger period
             *  
             *  we limit the query to return just the __ID so we are not sucking back full sessions
             */
            BasicDBObject query = new BasicDBObject();     
            query.put(MongoSessionManager.__ID,new BasicDBObject("$in", _sessionsIds ));
            query.put(MongoSessionManager.__ACCESSED, new BasicDBObject("$lt",System.currentTimeMillis() - _scavengeDelay));
            
            DBCursor checkSessions = _sessions.find(query, new BasicDBObject(MongoSessionManager.__ID, 1));
                        
            for ( DBObject session : checkSessions )
            {             	                    
                invalidateAll((String)session.get(MongoSessionManager.__ID));
            }
        } 
        
    }
    
    /* ------------------------------------------------------------ */
    /**
     * ScavengeFully is a process that periodically checks the tracked session
     * ids of this given instance of the session id manager to see if they 
     * are past the point of expiration.
     * 
     * NOTE: this is potentially devastating and may lead to serious session
     * coherence issues, not to be used in a running cluster
     */
    protected void scavengeFully()
    {        
        __log.debug("SessionIdManager:scavengeFully");

        DBCursor checkSessions = _sessions.find();

        for (DBObject session : checkSessions)
        {
            invalidateAll((String)session.get(MongoSessionManager.__ID));
        }

    }

    /* ------------------------------------------------------------ */
    /**
     * Purge is a process that cleans the mongodb cluster of old sessions that are no
     * longer valid.
     * 
     * There are two checks being done here:
     * 
     *  - if the accessed time is older then the current time minus the purge invalid age
     *    and it is no longer valid then remove that session
     *  - if the accessed time is older then the current time minus the purge valid age
     *    then we consider this a lost record and remove it
     *    
     *  NOTE: if your system supports long lived sessions then the purge valid age should be
     *  set to zero so the check is skipped.
     *  
     *  The second check was added to catch sessions that were being managed on machines 
     *  that might have crashed without marking their sessions as 'valid=false'
     */
    protected void purge()
    {
        BasicDBObject invalidQuery = new BasicDBObject();

        invalidQuery.put(MongoSessionManager.__ACCESSED, new BasicDBObject("$lt",System.currentTimeMillis() - _purgeInvalidAge));
        invalidQuery.put(MongoSessionManager.__VALID, __valid_false);
        
        DBCursor oldSessions = _sessions.find(invalidQuery, new BasicDBObject(MongoSessionManager.__ID, 1));

        for (DBObject session : oldSessions)
        {
            String id = (String)session.get("id");
            
            __log.debug("MongoSessionIdManager:purging invalid " + id);
            
            _sessions.remove(session);
        }

        if (_purgeValidAge != 0)
        {
            BasicDBObject validQuery = new BasicDBObject();

            validQuery.put(MongoSessionManager.__ACCESSED,new BasicDBObject("$lt",System.currentTimeMillis() - _purgeValidAge));
            validQuery.put(MongoSessionManager.__VALID, __valid_false);

            oldSessions = _sessions.find(invalidQuery,new BasicDBObject(MongoSessionManager.__ID,1));

            for (DBObject session : oldSessions)
            {
                String id = (String)session.get(MongoSessionManager.__ID);

                __log.debug("MongoSessionIdManager:purging valid " + id);

                _sessions.remove(session);
            }
        }

    }
    
    /* ------------------------------------------------------------ */
    /**
     * Purge is a process that cleans the mongodb cluster of old sessions that are no
     * longer valid.
     * 
     */
    protected void purgeFully()
    {
        BasicDBObject invalidQuery = new BasicDBObject();

        invalidQuery.put(MongoSessionManager.__VALID, false);
        
        DBCursor oldSessions = _sessions.find(invalidQuery, new BasicDBObject(MongoSessionManager.__ID, 1));
        
        for (DBObject session : oldSessions)
        {
            String id = (String)session.get(MongoSessionManager.__ID);
            
            __log.debug("MongoSessionIdManager:purging invalid " + id);
            
            _sessions.remove(session);
        }

    }
    
    
    /* ------------------------------------------------------------ */
    public DBCollection getSessions()
    {
        return _sessions;
    }
    
    
    /* ------------------------------------------------------------ */
    public boolean isPurgeEnabled()
    {
        return _purge;
    }
    
    /* ------------------------------------------------------------ */
    public void setPurge(boolean purge)
    {
        this._purge = purge;
    }

    /* ------------------------------------------------------------ */
    /**
     * sets the scavengeDelay
     */
    public void setScavengeDelay(long scavengeDelay)
    {
        this._scavengeDelay = scavengeDelay;  
    }


    /* ------------------------------------------------------------ */
    public void setScavengePeriod(long scavengePeriod)
    {
        this._scavengePeriod = scavengePeriod;
    }
    
    /* ------------------------------------------------------------ */
    public void setPurgeDelay(long purgeDelay)
    {
        if ( isRunning() )
        {
            throw new IllegalStateException();
        }
        
        this._purgeDelay = purgeDelay;
    }
 
    /* ------------------------------------------------------------ */
    public long getPurgeInvalidAge()
    {
        return _purgeInvalidAge;
    }

    /* ------------------------------------------------------------ */
    /**
     * sets how old a session is to be persisted past the point it is
     * no longer valid
     */
    public void setPurgeInvalidAge(long purgeValidAge)
    {
        this._purgeInvalidAge = purgeValidAge;
    } 
    
    /* ------------------------------------------------------------ */
    public long getPurgeValidAge()
    {
        return _purgeValidAge;
    }

    /* ------------------------------------------------------------ */
    /**
     * sets how old a session is to be persist past the point it is 
     * considered no longer viable and should be removed
     * 
     * NOTE: set this value to 0 to disable purging of valid sessions
     */
    public void setPurgeValidAge(long purgeValidAge)
    {
        this._purgeValidAge = purgeValidAge;
    } 

    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        __log.debug("MongoSessionIdManager:starting");
     
        /*
         * setup the scavenger thread
         */
        if (_scavengeDelay > 0)
        {
            _scavengeTimer = new Timer("MongoSessionIdScavenger",true);

            synchronized (this)
            {
                if (_scavengerTask != null)
                {
                    _scavengerTask.cancel();
                }
                
                _scavengerTask = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        scavenge();
                    }
                };
                
                _scavengeTimer.schedule(_scavengerTask,_scavengeDelay,_scavengePeriod);
            }
        }
        
        /*
         * if purging is enabled, setup the purge thread
         */
        if ( _purge )
        {
            _purgeTimer = new Timer("MongoSessionPurger", true);
            
            synchronized (this)
            {
                if (_purgeTask != null)
                {
                    _purgeTask.cancel();
                }
                _purgeTask = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        purge();
                    }
                };
                _purgeTimer.schedule(_purgeTask,_purgeDelay);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        if (_scavengeTimer != null)
        {
            _scavengeTimer.cancel();
            _scavengeTimer = null;
        }
        
        if (_purgeTimer != null)
        {
            _purgeTimer.cancel();
            _purgeTimer = null;
        }
        
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * is the session id known to mongo, and is it valid
     */
    public boolean idInUse(String sessionId)
    {        
        /*
         * optimize this query to only return the valid variable
         */
        DBObject o = _sessions.findOne(new BasicDBObject("id",sessionId), __valid_false);
        
        if ( o != null )
        {
            Boolean valid = (Boolean)o.get(MongoSessionManager.__VALID);
            
            if ( valid == null )
            {
                return false;
            }
            
            return valid;
        }
        
        return false;
    }

    /* ------------------------------------------------------------ */
    public void addSession(HttpSession session)
    {
        if (session == null)
        {
            return;
        }
        
        /*
         * already a part of the index in mongo...
         */
        
        __log.debug("MongoSessionIdManager:addSession:" + session.getId());
        
        synchronized (_sessionsIds)
        {
            _sessionsIds.add(session.getId());
        }
        
    }

    /* ------------------------------------------------------------ */
    public void removeSession(HttpSession session)
    {
        if (session == null)
        {
            return;
        }
        
        synchronized (_sessionsIds)
        {
            _sessionsIds.remove(session.getId());
        }
    }

    /* ------------------------------------------------------------ */
    public void invalidateAll(String sessionId)
    {
        synchronized (_sessionsIds)
        {
            _sessionsIds.remove(sessionId);
            
            
            //tell all contexts that may have a session object with this id to
            //get rid of them
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionHandler sessionHandler = (SessionHandler)((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null) 
                {
                    SessionManager manager = sessionHandler.getSessionManager();

                    if (manager != null && manager instanceof MongoSessionManager)
                    {
                        ((MongoSessionManager)manager).invalidateSession(sessionId);
                    }
                }
            }
        }      
    }

    /* ------------------------------------------------------------ */
    // TODO not sure if this is correct
    public String getClusterId(String nodeId)
    {
        int dot=nodeId.lastIndexOf('.');
        return (dot>0)?nodeId.substring(0,dot):nodeId;
    }

    /* ------------------------------------------------------------ */
    // TODO not sure if this is correct
    public String getNodeId(String clusterId, HttpServletRequest request)
    {
        if (_workerName!=null)
            return clusterId+'.'+_workerName;

        return clusterId;
    }

}

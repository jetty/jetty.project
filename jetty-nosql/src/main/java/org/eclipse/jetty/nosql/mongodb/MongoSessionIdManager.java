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

package org.eclipse.jetty.nosql.mongodb;


import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.AbstractSessionIdManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * Based partially on the JDBCSessionIdManager.
 * <p>
 * Theory is that we really only need the session id manager for the local 
 * instance so we have something to scavenge on, namely the list of known ids
 * <p>
 * This class has a timer that runs a periodic scavenger thread to query
 *  for all id's known to this node whose precalculated expiry time has passed.
 * <p>
 * These found sessions are then run through the invalidateAll(id) method that 
 * is a bit hinky but is supposed to notify all handlers this id is now DOA and 
 * ought to be cleaned up.  this ought to result in a save operation on the session
 * that will change the valid field to false (this conjecture is unvalidated atm)
 */
public class MongoSessionIdManager extends AbstractSessionIdManager
{
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");

    final static DBObject __version_1 = new BasicDBObject(MongoSessionManager.__VERSION,1);
    final static DBObject __valid_false = new BasicDBObject(MongoSessionManager.__VALID,false);
    final static DBObject __valid_true = new BasicDBObject(MongoSessionManager.__VALID,true);

    final static long __defaultScavengePeriod = 30 * 60 * 1000; // every 30 minutes
   
    
    final DBCollection _sessions;
    protected Server _server;
    protected Scheduler _scheduler;
    protected boolean _ownScheduler;
    protected Scheduler.Task _scavengerTask;
    protected Scheduler.Task _purgerTask;
 

    
    private long _scavengePeriod = __defaultScavengePeriod;
    

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
     */
    protected final Set<String> _sessionsIds = new ConcurrentHashSet<>();

    /**
     * The maximum number of items to return from a purge query.
     */
    private int _purgeLimit = 0;

    private int _scavengeBlockSize;
    
    
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
                idle();
            }
            finally
            {
                if (_scheduler != null && _scheduler.isRunning())
                    _scavengerTask = _scheduler.schedule(this, _scavengePeriod, TimeUnit.MILLISECONDS);
            }
        } 
    }
    
    
    /**
     * Purger
     *
     */
    protected class Purger implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                purge();
            }
            finally
            {
                if (_scheduler != null && _scheduler.isRunning())
                    _purgerTask = _scheduler.schedule(this, _purgeDelay, TimeUnit.MILLISECONDS);
            }
        }
    }
    
    
    

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

        DBObject idKey = BasicDBObjectBuilder.start().add("id", 1).get();        
        _sessions.createIndex(idKey,
                              BasicDBObjectBuilder.start()
                              .add("name", "id_1")
                              .add("ns", _sessions.getFullName())
                              .add("sparse", false)
                              .add("unique", true)
                              .get());

        DBObject versionKey = BasicDBObjectBuilder.start().add("id", 1).add("version", 1).get();       
        _sessions.createIndex(versionKey, BasicDBObjectBuilder.start()
                              .add("name", "id_1_version_1")
                              .add("ns", _sessions.getFullName())
                              .add("sparse", false)
                              .add("unique", true)
                              .get());
                            

        // index our accessed and valid fields so that purges are faster, note that the "valid" field is first
        // so that we can take advantage of index prefixes
        // http://docs.mongodb.org/manual/core/index-compound/#compound-index-prefix
        
        DBObject validKey = BasicDBObjectBuilder.start().add(MongoSessionManager.__VALID, 1).add(MongoSessionManager.__ACCESSED, 1).get();
        _sessions.createIndex(validKey,
                BasicDBObjectBuilder.start()
                .add("name", MongoSessionManager.__VALID+"_1_"+MongoSessionManager.__ACCESSED+"_1")
                .add("ns", _sessions.getFullName())
                .add("sparse", false)
                .add("background", true).get());
    }
 
    /* ------------------------------------------------------------ */
    /**
     * Scavenge is a process that periodically checks the tracked session
     * ids of this given instance of the session id manager to see if they 
     * are past the point of expiration.
     */
    protected void scavenge()
    {
        long now = System.currentTimeMillis();
        __log.debug("SessionIdManager:scavenge:at {}", now);        
        /*
         * run a query returning results that:
         *  - are in the known list of sessionIds
         *  - the expiry time has passed
         *  
         *  we limit the query to return just the __ID so we are not sucking back full sessions
         *  
         *  break scavenge query into blocks for faster mongo queries
         */
        Set<String> block = new HashSet<String>();
            
        Iterator<String> itor = _sessionsIds.iterator();
        while (itor.hasNext())
        {
            block.add(itor.next());
            if ((_scavengeBlockSize > 0) && (block.size() == _scavengeBlockSize))
            {
                //got a block
                scavengeBlock (now, block);
                //reset for next run
                block.clear();
            }
        }
        
        //non evenly divisble block size, or doing it all at once
        if (!block.isEmpty())
            scavengeBlock(now, block);
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Check a block of session ids for expiry and thus scavenge.
     * 
     * @param atTime purge at time
     * @param ids set of session ids
     */
    protected void scavengeBlock (long atTime, Set<String> ids)
    {
        if (ids == null)
            return;
        
        BasicDBObject query = new BasicDBObject();     
        query.put(MongoSessionManager.__ID,new BasicDBObject("$in", ids ));
        query.put(MongoSessionManager.__EXPIRY, new BasicDBObject("$gt", 0).append("$lt", atTime));   
            
        DBCursor checkSessions = _sessions.find(query, new BasicDBObject(MongoSessionManager.__ID, 1));
                        
        for ( DBObject session : checkSessions )
        {             
            __log.debug("SessionIdManager:scavenge: expiring session {}", (String)session.get(MongoSessionManager.__ID));
            expireAll((String)session.get(MongoSessionManager.__ID));
        }            
    }
    
    /* ------------------------------------------------------------ */
    /**
     * ScavengeFully will expire all sessions. In most circumstances
     * you should never need to call this method.
     * 
     * <b>USE WITH CAUTION</b>
     */
    protected void scavengeFully()
    {        
        __log.debug("SessionIdManager:scavengeFully");

        DBCursor checkSessions = _sessions.find();

        for (DBObject session : checkSessions)
        {
            expireAll((String)session.get(MongoSessionManager.__ID));
        }

    }

    /* ------------------------------------------------------------ */
    /**
     * Purge is a process that cleans the mongodb cluster of old sessions that are no
     * longer valid.
     * 
     * There are two checks being done here:
     * 
     *  - if the accessed time is older than the current time minus the purge invalid age
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
        __log.debug("PURGING");
        BasicDBObject invalidQuery = new BasicDBObject();

        invalidQuery.put(MongoSessionManager.__VALID, false);
        invalidQuery.put(MongoSessionManager.__ACCESSED, new BasicDBObject("$lt",System.currentTimeMillis() - _purgeInvalidAge));
        
        DBCursor oldSessions = _sessions.find(invalidQuery, new BasicDBObject(MongoSessionManager.__ID, 1));

        if (_purgeLimit > 0)
        {
            oldSessions.limit(_purgeLimit);
        }

        for (DBObject session : oldSessions)
        {
            String id = (String)session.get("id");
            
            __log.debug("MongoSessionIdManager:purging invalid session {}", id);
            
            _sessions.remove(session);
        }

        if (_purgeValidAge != 0)
        {
            BasicDBObject validQuery = new BasicDBObject();

            validQuery.put(MongoSessionManager.__VALID, true);
            validQuery.put(MongoSessionManager.__ACCESSED,new BasicDBObject("$lt",System.currentTimeMillis() - _purgeValidAge));

            oldSessions = _sessions.find(validQuery,new BasicDBObject(MongoSessionManager.__ID,1));

            if (_purgeLimit > 0)
            {
                oldSessions.limit(_purgeLimit);
            }

            for (DBObject session : oldSessions)
            {
                String id = (String)session.get(MongoSessionManager.__ID);

                __log.debug("MongoSessionIdManager:purging valid session {}", id);

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
            
            __log.debug("MongoSessionIdManager:purging invalid session {}", id);
            
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
     * The period in seconds between scavenge checks.
     * 
     * @param scavengePeriod the scavenge period in seconds
     */
    public void setScavengePeriod(long scavengePeriod)
    {
        if (scavengePeriod <= 0)
            _scavengePeriod = __defaultScavengePeriod;
        else
            _scavengePeriod = TimeUnit.SECONDS.toMillis(scavengePeriod);
    }

    /* ------------------------------------------------------------ */
    /** When scavenging, the max number of session ids in the query.
     * 
     * @param size the scavenge block size
     */
    public void setScavengeBlockSize (int size)
    {
        _scavengeBlockSize = size;
    }

    /* ------------------------------------------------------------ */
    public int getScavengeBlockSize ()
    {
        return _scavengeBlockSize;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * The maximum number of items to return from a purge query. If &lt;= 0 there is no limit. Defaults to 0
     * 
     * @param purgeLimit the purge limit 
     */
    public void setPurgeLimit(int purgeLimit)
    {
        _purgeLimit = purgeLimit;
    }

    /* ------------------------------------------------------------ */
    public int getPurgeLimit()
    {
        return _purgeLimit;
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
     * @param purgeValidAge the purge valid age
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
     * @param purgeValidAge the purge valid age
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


        synchronized (this)
        {
            //try and use a common scheduler, fallback to own
            _scheduler =_server.getBean(Scheduler.class);
            if (_scheduler == null)
            {
                _scheduler = new ScheduledExecutorScheduler();
                _ownScheduler = true;
                _scheduler.start();
            }   
            else if (!_scheduler.isStarted())
                throw new IllegalStateException("Shared scheduler not started");
            

            //setup the scavenger thread
            if (_scavengePeriod > 0)
            {
                if (_scavengerTask != null)
                {
                    _scavengerTask.cancel();
                    _scavengerTask = null;
                }

                _scavengerTask = _scheduler.schedule(new Scavenger(), _scavengePeriod, TimeUnit.MILLISECONDS);
            }
            else if (__log.isDebugEnabled())
                __log.debug("Scavenger disabled");


            //if purging is enabled, setup the purge thread
            if ( _purge )
            { 
                if (_purgerTask != null)
                {
                    _purgerTask.cancel();
                    _purgerTask = null;
                }
                _purgerTask = _scheduler.schedule(new Purger(), _purgeDelay, TimeUnit.MILLISECONDS);
            }
            else if (__log.isDebugEnabled())
                __log.debug("Purger disabled");
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void doStop() throws Exception
    {
        synchronized (this)
        {
            if (_scavengerTask != null)
            {
                _scavengerTask.cancel();
                _scavengerTask = null;
            }
 
            if (_purgerTask != null)
            {
                _purgerTask.cancel();
                _purgerTask = null;
            }
            
            if (_ownScheduler && _scheduler != null)
            {
                _scheduler.stop();
                _scheduler = null;
            }
        }
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    /**
     * Searches database to find if the session id known to mongo, and is it valid
     */
    @Override
    public boolean idInUse(String sessionId)
    {        
        /*
         * optimize this query to only return the valid variable
         */
        DBObject o = _sessions.findOne(new BasicDBObject("id",sessionId), __valid_true);
        
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
    @Override
    public void addSession(HttpSession session)
    {
        if (session == null)
        {
            return;
        }
        
        /*
         * already a part of the index in mongo...
         */
        
        __log.debug("MongoSessionIdManager:addSession {}", session.getId());
        
        _sessionsIds.add(session.getId());
        
    }

    /* ------------------------------------------------------------ */
    @Override
    public void removeSession(HttpSession session)
    {
        if (session == null)
        {
            return;
        }
        
        _sessionsIds.remove(session.getId());
    }

    /* ------------------------------------------------------------ */
    /** Remove the session id from the list of in-use sessions.
     * Inform all other known contexts that sessions with the same id should be
     * invalidated.
     * @see org.eclipse.jetty.server.SessionIdManager#invalidateAll(java.lang.String)
     */
    @Override
    public void invalidateAll(String sessionId)
    {
        _sessionsIds.remove(sessionId);
            
        //tell all contexts that may have a session object with this id to
        //get rid of them
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
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
 

    /* ------------------------------------------------------------ */
    /**
     * Expire this session for all contexts that are sharing the session 
     * id.
     * @param sessionId the session id
     */
    public void expireAll (String sessionId)
    {
        _sessionsIds.remove(sessionId);
            
            
        //tell all contexts that may have a session object with this id to
        //get rid of them
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null) 
            {
                SessionManager manager = sessionHandler.getSessionManager();

                if (manager != null && manager instanceof MongoSessionManager)
                {
                    ((MongoSessionManager)manager).expire(sessionId);
                }
            }
        }      
    }
    
    
    public void idle ()
    {
        //tell all contexts to passivate out idle sessions
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null) 
            {
                SessionManager manager = sessionHandler.getSessionManager();

                if (manager != null && manager instanceof MongoSessionManager)
                {
                    ((MongoSessionManager)manager).idle();
                }
            }
        }      
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());

        _sessionsIds.remove(oldClusterId);//remove the old one from the list
        _sessionsIds.add(newClusterId); //add in the new session id to the list

        //tell all contexts to update the id 
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null) 
            {
                SessionManager manager = sessionHandler.getSessionManager();

                if (manager != null && manager instanceof MongoSessionManager)
                {
                    ((MongoSessionManager)manager).renewSessionId(oldClusterId, oldNodeId, newClusterId, getNodeId(newClusterId, request));
                }
            }
        }
    }

}

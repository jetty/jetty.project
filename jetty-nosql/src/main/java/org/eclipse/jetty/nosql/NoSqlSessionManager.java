//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.nosql;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * NoSqlSessionManager
 *
 * Base class for SessionManager implementations using nosql frameworks
 * 
 */
public abstract class NoSqlSessionManager extends AbstractSessionManager implements SessionManager
{
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");

    protected final ConcurrentMap<String,NoSqlSession> _sessions=new ConcurrentHashMap<String,NoSqlSession>();

    private int _stalePeriod=0;
    private int _savePeriod=0;
    private int _idlePeriod=-1;
    private boolean _invalidateOnStop;
    private boolean _preserveOnStop = true;
    private boolean _saveAllAttributes;
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {
        super.doStart();
       
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void addSession(AbstractSession session)
    {
        if (isRunning())
        {
            //add into memory
            _sessions.put(session.getClusterId(),(NoSqlSession)session);
            //add into db
            ((NoSqlSession)session).save(true);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public AbstractSession getSession(String idInCluster)
    {
        NoSqlSession session = _sessions.get(idInCluster);
        __log.debug("getSession {} ", session );
        
        if (session==null)
        {
            //session not in this node's memory, load it
            session=loadSession(idInCluster);
            
            if (session!=null)
            {
                //session exists, check another request thread hasn't loaded it too
                NoSqlSession race=_sessions.putIfAbsent(idInCluster,session);
                if (race!=null)
                {
                    session.willPassivate();
                    session.clearAttributes();
                    session=race;
                }
                else
                    __log.debug("session loaded ", idInCluster);
                
                //check if the session we just loaded has actually expired, maybe while we weren't running
                if (getMaxInactiveInterval() > 0 && session.getAccessed() > 0 && ((getMaxInactiveInterval()*1000)+session.getAccessed()) < System.currentTimeMillis())
                {
                    __log.debug("session expired ", idInCluster);
                    expire(idInCluster);
                    session = null;
                }
            }
            else
                __log.debug("session does not exist {}", idInCluster);
        }

        return session;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void shutdownSessions() throws Exception
    {
        //If we are stopping, and we're preserving sessions, then we want to
        //save all of the sessions (including those that have been added during this method call)
        //and then just remove them from memory.
        
        //If we don't wish to preserve sessions and we're stopping, then we should invalidate
        //the session (which may remove it).
        long gracefulStopMs = getContextHandler().getServer().getStopTimeout();
        long stopTime = 0;
        if (gracefulStopMs > 0)
            stopTime = System.nanoTime() + (TimeUnit.NANOSECONDS.convert(gracefulStopMs, TimeUnit.MILLISECONDS));        
        
        ArrayList<NoSqlSession> sessions=new ArrayList<NoSqlSession>(_sessions.values());

        // loop while there are sessions, and while there is stop time remaining, or if no stop time, just 1 loop
        while (sessions.size() > 0 && ((stopTime > 0 && (System.nanoTime() < stopTime)) || (stopTime == 0)))
        {
            for (NoSqlSession session : sessions)
            {
                if (isPreserveOnStop())
                {
                    //we don't want to delete the session, so save the session
                    //and remove from memory
                    session.save(false);
                    _sessions.remove(session.getClusterId());
                }
                else
                {
                  //invalidate the session so listeners will be called and also removes the session
                  session.invalidate();
                }
            }
            
            //check if we should terminate our loop if we're not using the stop timer
            if (stopTime == 0)
            {
                break;
            }
            // Get any sessions that were added by other requests during processing and go around the loop again
            sessions=new ArrayList<NoSqlSession>(_sessions.values());
        }
    }
    

    /* ------------------------------------------------------------ */
    @Override
    protected AbstractSession newSession(HttpServletRequest request)
    {
        return new NoSqlSession(this,request);
    }

    /* ------------------------------------------------------------ */
    /** Remove the session from the in-memory list for this context.
     * Also remove the context sub-document for this session id from the db.
     * @see org.eclipse.jetty.server.session.AbstractSessionManager#removeSession(java.lang.String)
     */
    @Override
    protected boolean removeSession(String idInCluster)
    {
        synchronized (this)
        {
            NoSqlSession session = _sessions.remove(idInCluster);

            try
            {
                if (session != null)
                {
                    return remove(session);
                }
            }
            catch (Exception e)
            {
                __log.warn("Problem deleting session {}", idInCluster,e);
            }

            return session != null;
        }
    }

    /* ------------------------------------------------------------ */
    protected void expire( String idInCluster )
    {
        synchronized (this)
        {
            //get the session from memory
            NoSqlSession session = _sessions.get(idInCluster);

            try
            {
                if (session == null)
                {
                    //we need to expire the session with its listeners, so load it
                    session = loadSession(idInCluster);
                }
               
                if (session != null)
                    session.timeout();
            }
            catch (Exception e)
            {
                __log.warn("Problem expiring session {}", idInCluster,e);
            }
        }
    }
    
    
    public void invalidateSession (String idInCluster)
    {
        synchronized (this)
        {
            NoSqlSession session = _sessions.get(idInCluster);
            try
            {
                __log.debug("invalidating session {}", idInCluster);
                if (session != null)
                {
                    session.invalidate();
                }
            }
            catch (Exception e)
            {
                __log.warn("Problem invalidating session {}", idInCluster,e); 
            }
        }
    }

    
    /* ------------------------------------------------------------ */
    /**
     * The State Period is the maximum time in seconds that an in memory session is allows to be stale:
     * <ul>  
     * <li>If this period is exceeded, the DB will be checked to see if a more recent version is available.</li>
     * <li>If the state period is set to a value < 0, then no staleness check will be made.</li>
     * <li>If the state period is set to 0, then a staleness check is made whenever the active request count goes from 0 to 1.</li>
     * </ul>
     * @return the stalePeriod in seconds
     */
    public int getStalePeriod()
    {
        return _stalePeriod;
    }

    /* ------------------------------------------------------------ */
    /**
     * The State Period is the maximum time in seconds that an in memory session is allows to be stale:
     * <ul>  
     * <li>If this period is exceeded, the DB will be checked to see if a more recent version is available.</li>
     * <li>If the state period is set to a value < 0, then no staleness check will be made.</li>
     * <li>If the state period is set to 0, then a staleness check is made whenever the active request count goes from 0 to 1.</li>
     * </ul>
     * @param stalePeriod the stalePeriod in seconds
     */
    public void setStalePeriod(int stalePeriod)
    {
        _stalePeriod = stalePeriod;
    }

    /* ------------------------------------------------------------ */
    /**
     * The Save Period is the time in seconds between saves of a dirty session to the DB.  
     * When this period is exceeded, the a dirty session will be written to the DB: <ul>
     * <li>a save period of -2 means the session is written to the DB whenever setAttribute is called.</li>
     * <li>a save period of -1 means the session is never saved to the DB other than on a shutdown</li>
     * <li>a save period of 0 means the session is written to the DB whenever the active request count goes from 1 to 0.</li>
     * <li>a save period of 1 means the session is written to the DB whenever the active request count goes from 1 to 0 and the session is dirty.</li>
     * <li>a save period of > 1 means the session is written after that period in seconds of being dirty.</li>
     * </ul>
     * @return the savePeriod -2,-1,0,1 or the period in seconds >=2 
     */
    public int getSavePeriod()
    {
        return _savePeriod;
    }

    /* ------------------------------------------------------------ */
    /**
     * The Save Period is the time in seconds between saves of a dirty session to the DB.  
     * When this period is exceeded, the a dirty session will be written to the DB: <ul>
     * <li>a save period of -2 means the session is written to the DB whenever setAttribute is called.</li>
     * <li>a save period of -1 means the session is never saved to the DB other than on a shutdown</li>
     * <li>a save period of 0 means the session is written to the DB whenever the active request count goes from 1 to 0.</li>
     * <li>a save period of 1 means the session is written to the DB whenever the active request count goes from 1 to 0 and the session is dirty.</li>
     * <li>a save period of > 1 means the session is written after that period in seconds of being dirty.</li>
     * </ul>
     * @param savePeriod the savePeriod -2,-1,0,1 or the period in seconds >=2 
     */
    public void setSavePeriod(int savePeriod)
    {
        _savePeriod = savePeriod;
    }

    /* ------------------------------------------------------------ */
    /**
     * The Idle Period is the time in seconds before an in memory session is passivated.
     * When this period is exceeded, the session will be passivated and removed from memory.  If the session was dirty, it will be written to the DB.
     * If the idle period is set to a value < 0, then the session is never idled.
     * If the save period is set to 0, then the session is idled whenever the active request count goes from 1 to 0.
     * @return the idlePeriod
     */
    public int getIdlePeriod()
    {
        return _idlePeriod;
    }

    /* ------------------------------------------------------------ */
    /**
     * The Idle Period is the time in seconds before an in memory session is passivated.
     * When this period is exceeded, the session will be passivated and removed from memory.  If the session was dirty, it will be written to the DB.
     * If the idle period is set to a value < 0, then the session is never idled.
     * If the save period is set to 0, then the session is idled whenever the active request count goes from 1 to 0.
     * @param idlePeriod the idlePeriod in seconds
     */
    public void setIdlePeriod(int idlePeriod)
    {
        _idlePeriod = idlePeriod;
    }

    /* ------------------------------------------------------------ */
    /**
     * Invalidate sessions when the session manager is stopped otherwise save them to the DB.
     * @return the invalidateOnStop
     */
    public boolean isInvalidateOnStop()
    {
        return _invalidateOnStop;
    }

    /* ------------------------------------------------------------ */
    /**
     * Preserve sessions when the session manager is stopped otherwise remove them from the DB.
     * @return the removeOnStop
     */
    public boolean isPreserveOnStop()
    {
        return _preserveOnStop;
    }

    /* ------------------------------------------------------------ */
    /**
     * Invalidate sessions when the session manager is stopped otherwise save them to the DB.
     * @param invalidateOnStop the invalidateOnStop to set
     */
    public void setInvalidateOnStop(boolean invalidateOnStop)
    {
        _invalidateOnStop = invalidateOnStop;
    }

    /* ------------------------------------------------------------ */
    /**
     * Preserve sessions when the session manager is stopped otherwise remove them from the DB.
     * @param removeOnStop the removeOnStop to set
     */
    public void setPreserveOnStop(boolean preserveOnStop)
    {
        _preserveOnStop = preserveOnStop;
    }

    /* ------------------------------------------------------------ */
    /**
     * Save all attributes of a session or only update the dirty attributes.
     * @return the saveAllAttributes
     */
    public boolean isSaveAllAttributes()
    {
        return _saveAllAttributes;
    }

    /* ------------------------------------------------------------ */
    /**
     * Save all attributes of a session or only update the dirty attributes.
     * @param saveAllAttributes the saveAllAttributes to set
     */
    public void setSaveAllAttributes(boolean saveAllAttributes)
    {
        _saveAllAttributes = saveAllAttributes;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, String newClusterId, String newNodeId)
    {
        
        // Take the old session out of the list of sessions
        // Change to the new id
        // Put it back into the list of sessions
        // Update permanent storage

        synchronized (this)
        {
            try
            {
                NoSqlSession session = _sessions.remove(oldClusterId);
                update (session, newClusterId, newNodeId);
                session.setClusterId(newClusterId);
                session.setNodeId(newNodeId);
                _sessions.put(newClusterId, session);
            }
            catch (Exception e)
            {
                __log.warn(e);
            }
        }
        super.renewSessionId(oldClusterId, oldNodeId, newClusterId, newNodeId);
    }

    
    /* ------------------------------------------------------------ */
    abstract protected NoSqlSession loadSession(String clusterId);
    
    /* ------------------------------------------------------------ */
    abstract protected Object save(NoSqlSession session,Object version, boolean activateAfterSave);

    /* ------------------------------------------------------------ */
    abstract protected Object refresh(NoSqlSession session, Object version);

    /* ------------------------------------------------------------ */
    abstract protected boolean remove(NoSqlSession session);

    /* ------------------------------------------------------------ */
    abstract protected void update(NoSqlSession session, String newClusterId, String newNodeId) throws Exception;
    
}

package org.eclipse.jetty.nosql;
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

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.session.AbstractSession;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class NoSqlSessionManager extends AbstractSessionManager implements SessionManager
{
    private final static Logger __log = Log.getLogger("org.eclipse.jetty.server.session");

    protected final ConcurrentMap<String,NoSqlSession> _sessions=new ConcurrentHashMap<String,NoSqlSession>();

    private int _stalePeriod=0;
    private int _savePeriod=0;
    private int _idlePeriod=-1;
    private boolean _invalidateOnStop;
    private boolean _saveAllAttributes;
    
    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
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
            _sessions.put(session.getClusterId(),(NoSqlSession)session);
    }

    /* ------------------------------------------------------------ */
    @Override
    public AbstractSession getSession(String idInCluster)
    {
        NoSqlSession session = _sessions.get(idInCluster);
        
        __log.debug("getSession: " + session );
        
        if (session==null)
        {
            session=loadSession(idInCluster);
            
            if (session!=null)
            {
                NoSqlSession race=_sessions.putIfAbsent(idInCluster,session);
                if (race!=null)
                {
                    session.willPassivate();
                    session.clearAttributes();
                    session=race;
                }
            }
        }
        
        return session;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected void invalidateSessions() throws Exception
    {
        // Invalidate all sessions to cause unbind events
        ArrayList<NoSqlSession> sessions=new ArrayList<NoSqlSession>(_sessions.values());
        int loop=100;
        while (sessions.size()>0 && loop-->0)
        {
            // If we are called from doStop
            if (isStopping())
            {
                // Then we only save and remove the session - it is not invalidated.
                for (NoSqlSession session : sessions)
                {
                    session.save(false);
                    removeSession(session,false);
                }
            }
            else
            {
                for (NoSqlSession session : sessions)
                    session.invalidate();
            }
            
            // check that no new sessions were created while we were iterating
            sessions=new ArrayList<NoSqlSession>(_sessions.values());
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    protected AbstractSession newSession(HttpServletRequest request)
    {
        long created=System.currentTimeMillis();
        return new NoSqlSession(this,request);
    }

    /* ------------------------------------------------------------ */
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
                __log.warn("Problem deleting session id=" + idInCluster,e);
            }

            return session != null;
        }
    }

    /* ------------------------------------------------------------ */
    protected void invalidateSession( String idInCluster )
    {
        synchronized (this)
        {
            NoSqlSession session = _sessions.remove(idInCluster);

            try
            {
                if (session != null)
                {
                    remove(session);
                }
            }
            catch (Exception e)
            {
                __log.warn("Problem deleting session id=" + idInCluster,e);
            }
        }
        
        /*
         * ought we not go to cluster and mark it invalid?
         */
        
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
     * Invalidate sessions when the session manager is stopped otherwise save them to the DB.
     * @param invalidateOnStop the invalidateOnStop to set
     */
    public void setInvalidateOnStop(boolean invalidateOnStop)
    {
        _invalidateOnStop = invalidateOnStop;
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
    abstract protected NoSqlSession loadSession(String clusterId);
    
    /* ------------------------------------------------------------ */
    abstract protected Object save(NoSqlSession session,Object version, boolean activateAfterSave);

    /* ------------------------------------------------------------ */
    abstract protected Object refresh(NoSqlSession session, Object version);

    /* ------------------------------------------------------------ */
    abstract protected boolean remove(NoSqlSession session);
    
}

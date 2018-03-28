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


package org.eclipse.jetty.server.session;


import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * AbstractSessionDataStore
 *
 *
 */
@ManagedObject
public abstract class AbstractSessionDataStore extends ContainerLifeCycle implements SessionDataStore
{
    final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    protected SessionContext _context; //context associated with this session data store
    protected int _gracePeriodSec = 60 * 60; //default of 1hr 
    protected long _lastExpiryCheckTime = 0; //last time in ms that getExpired was called
    protected int _savePeriodSec = 0; //time in sec between saves

    /**
     * Store the session data persistently.
     * 
     * @param id identity of session to store
     * @param data info of the session
     * @param lastSaveTime time of previous save or 0 if never saved
     * @throws Exception if unable to store data
     */
    public abstract void doStore(String id, SessionData data, long lastSaveTime) throws Exception;

   
    /**
     * Implemented by subclasses to resolve which sessions this node
     * should attempt to expire.
     * 
     * @param candidates the ids of sessions the SessionDataStore thinks has expired
     * @return the reconciled set of session ids that this node should attempt to expire
     */
    public abstract Set<String> doGetExpired (Set<String> candidates);

    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    @Override
    public void initialize (SessionContext context) throws Exception
    {
        if (isStarted())
            throw new IllegalStateException("Context set after SessionDataStore started");
        _context = context;
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#store(java.lang.String, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public void store(String id, SessionData data) throws Exception
    {
        if (data == null)
            return;


        long lastSave = data.getLastSaved();
        long savePeriodMs = (_savePeriodSec <=0? 0: TimeUnit.SECONDS.toMillis(_savePeriodSec));
        
        if (LOG.isDebugEnabled())
            LOG.debug("Store: id={}, dirty={}, lsave={}, period={}, elapsed={}", id,data.isDirty(), data.getLastSaved(), savePeriodMs, (System.currentTimeMillis()-lastSave));

        //save session if attribute changed or never been saved or time between saves exceeds threshold
        if (data.isDirty() || (lastSave <= 0) || ((System.currentTimeMillis()-lastSave) > savePeriodMs))
        {
            //set the last saved time to now
            data.setLastSaved(System.currentTimeMillis());
            try
            {
                //call the specific store method, passing in previous save time
                doStore(id, data, lastSave);
                data.setDirty(false); //only undo the dirty setting if we saved it
            }
            catch (Exception e)
            {
                //reset last save time if save failed
                data.setLastSaved(lastSave);
                throw e;
            }
        }
    }
    


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#getExpired(java.util.Set)
     */
    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
        try
        {
            return doGetExpired (candidates);
        }
        finally
        {
            _lastExpiryCheckTime = System.currentTimeMillis();
        }
    }




    /** 
     * @see org.eclipse.jetty.server.session.SessionDataStore#newSessionData(java.lang.String, long, long, long, long)
     */
    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new SessionData(id, _context.getCanonicalContextPath(), _context.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }
 
    protected void checkStarted () throws IllegalStateException
    {
        if (isStarted())
            throw new IllegalStateException("Already started");
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_context == null)
            throw new IllegalStateException ("No SessionContext");
        
        super.doStart();
    }
    
    
    @ManagedAttribute(value="interval in secs to prevent too eager session scavenging", readonly=true)
    public int getGracePeriodSec()
    {
        return _gracePeriodSec;
    }

    public void setGracePeriodSec(int sec)
    {
        _gracePeriodSec = sec;
    }


    /**
     * @return the savePeriodSec
     */
    @ManagedAttribute(value="min secs between saves", readonly=true)
    public int getSavePeriodSec()
    {
        return _savePeriodSec;
    }


    /** 
     * The minimum time in seconds between save operations.
     * Saves normally occur every time the last request 
     * exits as session. If nothing changes on the session
     * except for the access time and the persistence technology
     * is slow, this can cause delays.
     * <p>
     * By default the value is 0, which means we save 
     * after the last request exists. A non zero value
     * means that we will skip doing the save if the
     * session isn't dirty if the elapsed time since
     * the session was last saved does not exceed this
     * value.
     * 
     * @param savePeriodSec the savePeriodSec to set
     */
    public void setSavePeriodSec(int savePeriodSec)
    {
        _savePeriodSec = savePeriodSec;
    }


    /** 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
       return String.format("%s@%x[passivating=%b,graceSec=%d]",this.getClass().getName(),this.hashCode(),isPassivating(),getGracePeriodSec());

    }

    
}

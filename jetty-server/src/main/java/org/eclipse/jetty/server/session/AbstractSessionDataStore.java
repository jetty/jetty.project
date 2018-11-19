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
import java.util.concurrent.atomic.AtomicReference;

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
    protected long _lastOrphanSweepTime = 0; //last time in ms that we deleted orphaned sessions
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
     * Load the session from persistent store.
     * 
     * @param id the id of the session to load
     * @return the re-inflated session
     * 
     * @throws Exception if unable to load the session
     */
    public abstract SessionData doLoad (String id) throws Exception;

   
    /**
     * Implemented by subclasses to resolve which sessions in this context 
     * that are being managed by this node that should be expired.
     * 
     * @param candidates the ids of sessions the SessionCache thinks has expired
     * @return the reconciled set of session ids that have been checked in the store
     */
    public abstract Set<String> doGetExpired (Set<String> candidates);
    
    
    /**
     * Implemented by subclasses to find sessions for this context in the store that
     * expired at or before the timeLimit and thus not being actively managed by
     * any node. This method is only called periodically (the period
     * is configurable) to avoid putting too much load on the store.
     * 
     * @param timeLimit the upper limit of expiry times to check. Sessions
     * expired at or before this time will match.
     * 
     * @return the empty set if there are no sessions expired as at the timeLimit, or
     * otherwise a set of session ids.
     */
    public abstract Set<String> doGetOldExpired (long timeLimit);
    
    
    /**
     * Implemented by subclasses to delete sessions for other contexts that
     * expired at or before the timeLimit. These are 'orphaned' sessions
     * that are no longer being actively managed by any node. These are 
     * explicitly sessions that do NOT belong to this context (other mechanisms
     * such as doGetOrphanedExpired take care of those). As they don't belong
     * to this context, they could not be loaded by us.
     * 
     * This is called only periodically to avoid placing excessive load on the store.
     * 
     * @param timeLimit
     */
    public abstract void cleanOrphans (long timeLimit);

    @Override
    public void initialize (SessionContext context) throws Exception
    {
        if (isStarted())
            throw new IllegalStateException("Context set after SessionDataStore started");
        _context = context;
    }

    
    
    @Override
    public SessionData load(String id) throws Exception
    {
        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
        
        Runnable r = new Runnable()
        {
            @Override
            public void run ()
            {
                try
                {
                    reference.set(doLoad(id));
                }
                catch (Exception e)
                {
                    exception.set(e);
                }
            }
        };

        _context.run(r);
        if (exception.get() != null)
            throw exception.get();
        
        return reference.get();
    }


    @Override
    public void store(String id, SessionData data) throws Exception
    {
        if (data == null)
            return;

        final AtomicReference<Exception> exception = new AtomicReference<Exception>();

        Runnable r = new Runnable()
        {
            @Override
            public void run ()
            {
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
                        exception.set(e);
                    }
                }
            };
        };

        _context.run(r);
        if (exception.get() != null)
            throw exception.get();
    }
    

    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
        //always verify the set of candidates we've been given
        //by the sessioncache
        Set<String> expired = doGetExpired (candidates);

        long now = System.currentTimeMillis();

        //only periodically check the backing store to find other sessions
        //in this context that expired long ago (ie not being actively managed
        //by any node)
        long expiryTimeLimit = 0;
        //if first check then find sessions that expired 3 grace periods ago.
        //this ensures that on startup we don't find sessions that are expired
        //but being managed by another node.
        if (_lastExpiryCheckTime <= 0)
            expiryTimeLimit = now - TimeUnit.SECONDS.toMillis(_gracePeriodSec*3);
        else
        {
            //only do the check once every gracePeriod to avoid expensive searches
            if (now > (_lastExpiryCheckTime+TimeUnit.SECONDS.toMillis(_gracePeriodSec)))
                expiryTimeLimit = now - TimeUnit.SECONDS.toMillis(_gracePeriodSec);
        }
        if (expiryTimeLimit > 0)
        {   
            if (LOG.isDebugEnabled()) LOG.debug("Searching for old expired sessions for context {}", _context.getCanonicalContextPath());
            try
            {
                expired.addAll(doGetOldExpired(expiryTimeLimit));
            }
            finally
            {
                _lastExpiryCheckTime = now;
            }
        }
        //periodically comb the backing store to delete sessions for other
        //other contexts that expired a long time ago (ie not being actively
        //managed by any node).
        if (_lastOrphanSweepTime > 0 && (now > (_lastOrphanSweepTime+TimeUnit.SECONDS.toMillis(10*_gracePeriodSec))))
        {   
            try
            {
                if (LOG.isDebugEnabled()) LOG.debug("Cleaning orphans");
                cleanOrphans(now - TimeUnit.SECONDS.toMillis(10*_gracePeriodSec));
            }
            finally
            {
                _lastOrphanSweepTime = now;            
            }
        }
        return expired;
    }


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

    @Override
    public String toString()
    {
       return String.format("%s@%x[passivating=%b,graceSec=%d]",this.getClass().getName(),this.hashCode(),isPassivating(),getGracePeriodSec());

    }

    
}

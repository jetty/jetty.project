//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractSessionDataStore
 */
@ManagedObject
public abstract class AbstractSessionDataStore extends ContainerLifeCycle implements SessionDataStore
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractSessionDataStore.class);

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
     * @throws Exception if unable to load the session
     */
    public abstract SessionData doLoad(String id) throws Exception;

    /**
     * Implemented by subclasses to resolve which sessions in this context 
     * that are being managed by this node that should be expired.
     *
     * @param candidates the ids of sessions the SessionCache thinks has expired
     * @param time the time at which to check for expiry
     * @return the reconciled set of session ids that have been checked in the store
     */
    public abstract Set<String> doGetExpired(Set<String> candidates, long time);
    
    /**
     * Implemented by subclasses to find sessions for this context in the store
     * that expired at or before the time limit and thus not being actively
     * managed by any node. This method is only called periodically (the period
     * is configurable) to avoid putting too much load on the store.
     * 
     * @param before the upper limit of expiry times to check. Sessions expired
     *            at or before this timestamp will match.
     * 
     * @return the empty set if there are no sessions expired as at the time, or
     *         otherwise a set of session ids.
     */
    public abstract Set<String> doGetExpired(long before);
    
    /**
     * Implemented by subclasses to delete sessions for other contexts that
     * expired at or before the timeLimit. These are 'orphaned' sessions that
     * are no longer being actively managed by any node. These are explicitly
     * sessions that do NOT belong to this context (other mechanisms such as
     * doGetExpired take care of those). As they don't belong to this context,
     * they cannot be loaded by us.
     * 
     * This is called only periodically to avoid placing excessive load on the
     * store.
     * 
     * @param time the upper limit of the expiry time to check in msec
     */
    public abstract void doCleanOrphans(long time);

    @Override
    public void initialize(SessionContext context) throws Exception
    {
        if (isStarted())
            throw new IllegalStateException("Context set after SessionDataStore started");
        _context = context;
    }
    
    /**
     * Remove all sessions for any context that expired at or before the given time.
     * @param timeLimit the time before which the sessions must have expired.
     * @throws Exception
     */
    public void cleanOrphans(long timeLimit) throws Exception
    {
        if (!isStarted())
            throw new IllegalStateException("Not started");
        
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();
    
        Runnable r = () ->
        {
            try
            {
                doCleanOrphans(timeLimit);
            }
            catch (Exception e)
            {
                exception.set(e);
            }
        };
    
        _context.run(r);
        if (exception.get() != null)
            throw exception.get();
    }

    @Override
    public SessionData load(String id) throws Exception
    {
        if (!isStarted())
            throw new IllegalStateException("Not started");

        final AtomicReference<SessionData> reference = new AtomicReference<SessionData>();
        final AtomicReference<Exception> exception = new AtomicReference<Exception>();

        Runnable r = () ->
        {
            try
            {
                reference.set(doLoad(id));
            }
            catch (Exception e)
            {
                exception.set(e);
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
        if (!isStarted())
            throw new IllegalStateException("Not started");
        
        if (data == null)
            return;

        final AtomicReference<Exception> exception = new AtomicReference<Exception>();

        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                long lastSave = data.getLastSaved();
                long savePeriodMs = (_savePeriodSec <= 0 ? 0 : TimeUnit.SECONDS.toMillis(_savePeriodSec));

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Store: id={}, mdirty={}, dirty={}, lsave={}, period={}, elapsed={}", id, data.isMetaDataDirty(),
                        data.isDirty(), data.getLastSaved(), savePeriodMs, (System.currentTimeMillis() - lastSave));
                }

                //save session if attribute changed, never been saved or metadata changed (eg expiry time) and save interval exceeded
                if (data.isDirty() || (lastSave <= 0) ||
                    (data.isMetaDataDirty() && ((System.currentTimeMillis() - lastSave) >= savePeriodMs)))
                {
                    //set the last saved time to now
                    data.setLastSaved(System.currentTimeMillis());
                    try
                    {
                        //call the specific store method, passing in previous save time
                        doStore(id, data, lastSave);
                        data.clean(); //unset all dirty flags
                    }
                    catch (Exception e)
                    {
                        //reset last save time if save failed
                        data.setLastSaved(lastSave);
                        exception.set(e);
                    }
                }
            }

            ;
        };

        _context.run(r);
        if (exception.get() != null)
            throw exception.get();
    }

    @Override
    public Set<String> getExpired(Set<String> candidates)
    {
        if (!isStarted())
            throw new IllegalStateException("Not started");
        long now = System.currentTimeMillis();
        
        // 1. always verify the set of candidates we've been given
        //by the sessioncache
        Set<String> expired = new HashSet<>();
        Set<String> expiredCandidates = doGetExpired(candidates, now);
        if (expiredCandidates != null)
            expired.addAll(expiredCandidates);

        // 2. check the backing store to find other sessions
        // in THIS context that expired long ago (ie cannot be actively managed
        //by any node)
        try
        {
            long expiryTimeLimit = 0;

            // if we have never checked for old expired sessions, then only find
            // those that are very old so we don't find sessions that other nodes
            // that are also starting up find
            if (_lastExpiryCheckTime <= 0)
                expiryTimeLimit = now - TimeUnit.SECONDS.toMillis(_gracePeriodSec * 3);
            else
            {
                // only do the check once every gracePeriod to avoid expensive searches,
                // and find sessions that expired at least one gracePeriod ago
                if (now > (_lastExpiryCheckTime + TimeUnit.SECONDS.toMillis(_gracePeriodSec)))
                    expiryTimeLimit = now - TimeUnit.SECONDS.toMillis(_gracePeriodSec);
            }

            if (expiryTimeLimit > 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Searching for sessions expired before {} for context {}", expiryTimeLimit, _context.getCanonicalContextPath());
                Set<String> tmp = doGetExpired(expiryTimeLimit);
                if (tmp != null)
                    expired.addAll(tmp);
            }
        }
        finally
        {
            _lastExpiryCheckTime = now;
        }

        // 3. Periodically but infrequently comb the backing store to delete sessions for 
        // OTHER contexts that expired a very long time ago (ie not being actively
        // managed by any node). As these sessions are not for our context, we 
        // can't load them, so they must just be forcibly deleted.
        try
        {
            if (now > (_lastOrphanSweepTime + TimeUnit.SECONDS.toMillis(10 * _gracePeriodSec)))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Cleaning orphans at {}, last sweep at {}", now, _lastOrphanSweepTime);
                doCleanOrphans(now - TimeUnit.SECONDS.toMillis(10 * _gracePeriodSec));
            }
        }
        finally
        {
            _lastOrphanSweepTime = now;
        }

        return expired;
    }

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new SessionData(id, _context.getCanonicalContextPath(), _context.getVhost(), created, accessed, lastAccessed, maxInactiveMs);
    }

    protected void checkStarted() throws IllegalStateException
    {
        if (isStarted())
            throw new IllegalStateException("Already started");
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_context == null)
            throw new IllegalStateException("No SessionContext");

        super.doStart();
    }

    @ManagedAttribute(value = "interval in secs to prevent too eager session scavenging", readonly = true)
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
    @ManagedAttribute(value = "min secs between saves", readonly = true)
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
        return String.format("%s@%x[passivating=%b,graceSec=%d]", this.getClass().getName(), this.hashCode(), isPassivating(), getGracePeriodSec());
    }
}

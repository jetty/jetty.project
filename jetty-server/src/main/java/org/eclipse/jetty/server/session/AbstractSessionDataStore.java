//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
 */
@ManagedObject
public abstract class AbstractSessionDataStore extends ContainerLifeCycle implements SessionDataStore
{
    static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

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
     * Load the session from persistent store.
     *
     * @param id the id of the session to load
     * @return the re-inflated session
     * @throws Exception if unable to load the session
     */
    public abstract SessionData doLoad(String id) throws Exception;

    /**
     * Implemented by subclasses to resolve which sessions this node
     * should attempt to expire.
     *
     * @param candidates the ids of sessions the SessionDataStore thinks has expired
     * @return the reconciled set of session ids that this node should attempt to expire
     */
    public abstract Set<String> doGetExpired(Set<String> candidates);

    @Override
    public void initialize(SessionContext context) throws Exception
    {
        if (isStarted())
            throw new IllegalStateException("Context set after SessionDataStore started");
        _context = context;
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
        
        try
        {
            return doGetExpired(candidates);
        }
        finally
        {
            _lastExpiryCheckTime = System.currentTimeMillis();
        }
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

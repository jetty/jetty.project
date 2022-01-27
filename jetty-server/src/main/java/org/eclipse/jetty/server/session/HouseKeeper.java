//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HouseKeeper
 *
 * There is 1 session HouseKeeper per SessionIdManager instance.
 */
@ManagedObject
public class HouseKeeper extends AbstractLifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(HouseKeeper.class);
    public static final long DEFAULT_PERIOD_MS = 1000L * 60 * 10;

    private final AutoLock _lock = new AutoLock();
    protected SessionIdManager _sessionIdManager;
    protected Scheduler _scheduler;
    protected Scheduler.Task _task; //scavenge task
    protected Runner _runner;
    protected boolean _ownScheduler = false;
    private long _intervalMs = DEFAULT_PERIOD_MS;

    /**
     * Runner
     */
    protected class Runner implements Runnable
    {

        @Override
        public void run()
        {
            try
            {
                scavenge();
            }
            finally
            {
                try (AutoLock l = _lock.lock())
                {
                    if (_scheduler != null && _scheduler.isRunning())
                        _task = _scheduler.schedule(this, _intervalMs, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * SessionIdManager associated with this scavenger
     *
     * @param sessionIdManager the session id manager
     */
    public void setSessionIdManager(SessionIdManager sessionIdManager)
    {
        if (isStarted())
            throw new IllegalStateException("HouseKeeper started");
        _sessionIdManager = sessionIdManager;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_sessionIdManager == null)
            throw new IllegalStateException("No SessionIdManager for Housekeeper");

        setIntervalSec(getIntervalSec());

        super.doStart();
    }

    /**
     * If scavenging is not scheduled, schedule it.
     *
     * @throws Exception if any error during scheduling the scavenging
     */
    protected void startScavenging() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (_scheduler == null)
            {
                if (_sessionIdManager instanceof DefaultSessionIdManager)
                {
                    //try and use a common scheduler, fallback to own
                    _scheduler = ((DefaultSessionIdManager)_sessionIdManager).getServer().getBean(Scheduler.class);
                }

                if (_scheduler == null)
                {
                    _scheduler = new ScheduledExecutorScheduler(String.format("Session-HouseKeeper-%x", hashCode()), false);
                    _ownScheduler = true;
                    _scheduler.start();
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} using own scheduler for scavenging", _sessionIdManager.getWorkerName());
                }
                else if (!_scheduler.isStarted())
                    throw new IllegalStateException("Shared scheduler not started");
            }

            //cancel any previous task
            if (_task != null)
                _task.cancel();
            if (_runner == null)
                _runner = new Runner();
            if (LOG.isDebugEnabled())
                LOG.debug("{} scavenging every {}ms", _sessionIdManager.getWorkerName(), _intervalMs);
            _task = _scheduler.schedule(_runner, _intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * If scavenging is scheduled, stop it.
     *
     * @throws Exception if any error during stopping the scavenging
     */
    protected void stopScavenging() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (_task != null)
            {
                _task.cancel();
                if (LOG.isDebugEnabled())
                    LOG.debug("{} stopped scavenging", _sessionIdManager.getWorkerName());
            }
            _task = null;
            if (_ownScheduler && _scheduler != null)
            {
                _ownScheduler = false;
                _scheduler.stop();
                _scheduler = null;
            }
            _runner = null;
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            stopScavenging();
            _scheduler = null;
        }
        super.doStop();
    }

    /**
     * Set the period between scavenge cycles
     *
     * @param sec the interval (in seconds)
     * @throws Exception if any error during restarting the scavenging
     */
    public void setIntervalSec(long sec) throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (isStarted() || isStarting())
            {
                if (sec <= 0)
                {
                    _intervalMs = 0L;
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} scavenging disabled", _sessionIdManager.getWorkerName());
                    stopScavenging();
                }
                else
                {
                    if (sec < 10)
                        LOG.warn("{} short interval of {}sec for session scavenging.", _sessionIdManager.getWorkerName(), sec);

                    _intervalMs = sec * 1000L;

                    //add a bit of variability into the scavenge time so that not all
                    //nodes with the same scavenge interval sync up
                    long tenPercent = _intervalMs / 10;
                    if ((System.currentTimeMillis() % 2) == 0)
                        _intervalMs += tenPercent;

                    if (isStarting() || isStarted())
                    {
                        startScavenging();
                    }
                }
            }
            else
            {
                _intervalMs = sec * 1000L;
            }
        }
    }

    /**
     * Get the period between scavenge cycles.
     *
     * @return the interval (in seconds)
     */
    @ManagedAttribute(value = "secs between scavenge cycles", readonly = true)
    public long getIntervalSec()
    {
        try (AutoLock l = _lock.lock())
        {
            return _intervalMs / 1000;
        }
    }

    /**
     * Periodically do session housekeeping
     */
    public void scavenge()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("{} scavenging sessions", _sessionIdManager.getWorkerName());

        //find the session managers
        for (SessionHandler manager : _sessionIdManager.getSessionHandlers())
        {
            if (manager != null)
            {
                try
                {
                    manager.scavenge();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to scavenge", e);
                }
            }
        }
    }

    @Override
    public String toString()
    {
        try (AutoLock l = _lock.lock())
        {
            return super.toString() + "[interval=" + _intervalMs + ", ownscheduler=" + _ownScheduler + "]";
        }
    }
}

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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * HouseKeeper
 *
 * There is 1 session HouseKeeper per SessionIdManager instance.
 */
@ManagedObject
public class HouseKeeper extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    public static final long DEFAULT_PERIOD_MS = 1000L * 60 * 10;
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
                if (_scheduler != null && _scheduler.isRunning())
                    _task = _scheduler.schedule(this, _intervalMs, TimeUnit.MILLISECONDS);
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
        _sessionIdManager = sessionIdManager;
    }

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_sessionIdManager == null)
            throw new IllegalStateException("No SessionIdManager for Housekeeper");

        setIntervalSec(getIntervalSec());

        super.doStart();
    }

    /**
     * Get a scheduler. First try a common scheduler, failing that
     * create our own.
     *
     * @throws Exception when the scheduler cannot be started
     */
    protected void findScheduler() throws Exception
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
                    LOG.debug("Using own scheduler for scavenging");
            }
            else if (!_scheduler.isStarted())
                throw new IllegalStateException("Shared scheduler not started");
        }
    }

    /**
     * If scavenging is not scheduled, schedule it.
     *
     * @throws Exception if any error during scheduling the scavenging
     */
    protected void startScavenging() throws Exception
    {
        synchronized (this)
        {
            if (_scheduler != null)
            {
                //cancel any previous task
                if (_task != null)
                    _task.cancel();
                if (_runner == null)
                    _runner = new Runner();
                LOG.info("{} Scavenging every {}ms", _sessionIdManager.getWorkerName(), _intervalMs);
                _task = _scheduler.schedule(_runner, _intervalMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * If scavenging is scheduled, stop it.
     *
     * @throws Exception if any error during stopping the scavenging
     */
    protected void stopScavenging() throws Exception
    {
        synchronized (this)
        {
            if (_task != null)
            {
                _task.cancel();
                LOG.info("{} Stopped scavenging", _sessionIdManager.getWorkerName());
            }
            _task = null;
            if (_ownScheduler && _scheduler != null)
            {
                _ownScheduler = false;
                _scheduler.stop();
                _scheduler = null;
            }
        }
        _runner = null;
    }

    /**
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        synchronized (this)
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
        if (isStarted() || isStarting())
        {
            if (sec <= 0)
            {
                _intervalMs = 0L;
                LOG.info("{} Scavenging disabled", _sessionIdManager.getWorkerName());
                stopScavenging();
            }
            else
            {
                if (sec < 10)
                    LOG.warn("{} Short interval of {}sec for session scavenging.", _sessionIdManager.getWorkerName(), sec);

                _intervalMs = sec * 1000L;

                //add a bit of variability into the scavenge time so that not all
                //nodes with the same scavenge interval sync up
                long tenPercent = _intervalMs / 10;
                if ((System.currentTimeMillis() % 2) == 0)
                    _intervalMs += tenPercent;

                if (isStarting() || isStarted())
                {
                    findScheduler();
                    startScavenging();
                }
            }
        }
        else
        {
            _intervalMs = sec * 1000L;
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
        return _intervalMs / 1000;
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
                    LOG.warn(e);
                }
            }
        }
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
        return super.toString() + "[interval=" + _intervalMs + ", ownscheduler=" + _ownScheduler + "]";
    }
}

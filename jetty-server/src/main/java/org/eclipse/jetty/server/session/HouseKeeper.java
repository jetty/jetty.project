//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * HouseKeeper
 *
 * There is 1 session HouseKeeper per SessionIdManager instance.
 *
 */
public class HouseKeeper extends AbstractLifeCycle
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    public static final long DEFAULT_PERIOD_MS = 1000L * 60 * 10;
    protected SessionIdManager _sessionIdManager;
    protected Scheduler _scheduler;
    protected Scheduler.Task _task; //scavenge task
    protected Runner _runner;
    protected boolean _ownScheduler = false;
    private long _intervalMs =  DEFAULT_PERIOD_MS;
   
    
   
    
    /**
     * Runner
     *
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
     * @param sessionIdManager the session id manager
     */
    public void setSessionIdManager (SessionIdManager sessionIdManager)
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
            throw new IllegalStateException ("No SessionIdManager for Housekeeper");
        

        if (_sessionIdManager instanceof DefaultSessionIdManager)
        {
            //try and use a common scheduler, fallback to own
            _scheduler = ((DefaultSessionIdManager)_sessionIdManager).getServer().getBean(Scheduler.class);
        }

        if (_scheduler == null)
        {
            _scheduler = new ScheduledExecutorScheduler();
            _ownScheduler = true;
            _scheduler.start();
        }
        else if (!_scheduler.isStarted())
            throw new IllegalStateException("Shared scheduler not started");

        setIntervalSec(getIntervalSec());
        
        super.doStart();
    }

    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        synchronized(this)
        {
            if (_task != null)
                _task.cancel();
            _task=null;
            if (_ownScheduler && _scheduler !=null)
                _scheduler.stop();
            _scheduler = null;
            _runner = null;
        }
        super.doStop();
    }
    
    
    /**
     * Set the period between scavenge cycles
     * @param sec the interval (in seconds)
     */
    public void setIntervalSec (long sec)
    {
        if (sec<=0)
            sec=60;

        long old_period=_intervalMs;
        long period=sec*1000L;

        _intervalMs=period;

        //add a bit of variability into the scavenge time so that not all
        //nodes with the same scavenge interval sync up
        long tenPercent = _intervalMs/10;
        if ((System.currentTimeMillis()%2) == 0)
            _intervalMs += tenPercent;

        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging every "+_intervalMs+" ms");
        
        synchronized (this)
        {
            if (_scheduler != null && (period!=old_period || _task==null))
            {
                if (_task!=null)
                    _task.cancel();
                if (_runner == null)
                    _runner = new Runner();
                _task = _scheduler.schedule(_runner,_intervalMs,TimeUnit.MILLISECONDS);
            }
        }
    }

    
    
    /**
     * Get the period between scavenge cycles.
     * 
     * @return the interval (in seconds)
     */
    public long getIntervalSec ()
    {
        return _intervalMs/1000;
    }
    
    
    
    /**
     * Periodically do session housekeeping
     */
    public void scavenge ()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging sessions");
        
        //find the session managers
        for (SessionHandler manager:_sessionIdManager.getSessionHandlers())
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
        return super.toString()+"[interval="+_intervalMs+", ownscheduler="+_ownScheduler+"]";
    }

}

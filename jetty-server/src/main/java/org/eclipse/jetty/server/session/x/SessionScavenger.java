//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session.x;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * SessionScavenger
 *
 * There is 1 session scavenger per SessionIdManager
 *
 */
public class SessionScavenger extends AbstractLifeCycle
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    public static final long DEFAULT_SCAVENGE_MS = 1000L * 60 * 10;
    protected SessionIdManager _sessionIdManager;
    protected Scheduler _scheduler;
    protected Scheduler.Task _task; //scavenge task
    protected ScavengerRunner _runner;
    protected boolean _ownScheduler = false;
    private long _scavengeIntervalMs =  DEFAULT_SCAVENGE_MS;
    
    
    
    /**
     * ScavengerRunner
     *
     */
    protected class ScavengerRunner implements Runnable
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
                   _task = _scheduler.schedule(this, _scavengeIntervalMs, TimeUnit.MILLISECONDS);
           }
        }
    }
    
    public void setSessionIdManager (SessionIdManager sessionIdManager)
    {
        _sessionIdManager = sessionIdManager;
    }
    
    
    @Override
    protected void doStart() throws Exception
    {
        if (_sessionIdManager == null)
            throw new IllegalStateException ("No SessionIdManager for Scavenger");
        
        if (!(_sessionIdManager instanceof AbstractSessionIdManager))
            throw new IllegalStateException ("SessionIdManager is not an AbstractSessionIdManager");


        //try and use a common scheduler, fallback to own
        _scheduler = ((AbstractSessionIdManager)_sessionIdManager).getServer().getBean(Scheduler.class);

        if (_scheduler == null)
        {
            _scheduler = new ScheduledExecutorScheduler();
            _ownScheduler = true;
            _scheduler.start();
        }
        else if (!_scheduler.isStarted())
            throw new IllegalStateException("Shared scheduler not started");


        setScavengeIntervalSec(getScavengeIntervalSec());
        
        super.doStart();
    }

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
    
    
    public void setScavengeIntervalSec (long sec)
    {
        if (sec<=0)
            sec=60;

        long old_period=_scavengeIntervalMs;
        long period=sec*1000L;

        _scavengeIntervalMs=period;

        //add a bit of variability into the scavenge time so that not all
        //nodes with the same scavenge interval sync up
        long tenPercent = _scavengeIntervalMs/10;
        if ((System.currentTimeMillis()%2) == 0)
            _scavengeIntervalMs += tenPercent;

        if (LOG.isDebugEnabled())
            LOG.debug("Scavenging every "+_scavengeIntervalMs+" ms");
        
        synchronized (this)
        {
            if (_scheduler != null && (period!=old_period || _task==null))
            {
                if (_task!=null)
                    _task.cancel();
                if (_runner == null)
                    _runner = new ScavengerRunner();
                _task = _scheduler.schedule(_runner,_scavengeIntervalMs,TimeUnit.MILLISECONDS);
            }
        }
    }

    public long getScavengeIntervalSec ()
    {
        return _scavengeIntervalMs/1000;
    }
    
    public void scavenge ()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        //find the session managers
        Handler[] contexts = ((AbstractSessionIdManager)_sessionIdManager).getServer().getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null)
            {
                SessionManager manager = (SessionManager) sessionHandler.getSessionManager();
                if (manager != null)
                {
                    //call scavenge on each manager to find keys for sessions that have expired
                    Set<SessionKey> expiredKeys = manager.scavenge();
                    for (SessionKey key:expiredKeys)
                    {
                        try
                        {
                            ((AbstractSessionIdManager)_sessionIdManager).invalidateAll(key.getId());
                        }
                        catch (Exception e)
                        {
                            LOG.warn(e);
                        }
                    }

                }
            }
        }

    }

}

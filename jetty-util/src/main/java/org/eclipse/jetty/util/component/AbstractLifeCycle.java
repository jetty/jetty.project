// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.util.component;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;

/**
 * Basic implementation of the life cycle interface for components.
 * 
 * 
 */
public abstract class AbstractLifeCycle implements LifeCycle
{
    public static final String STOPPED="STOPPED";
    public static final String FAILED="FAILED";
    public static final String STARTING="STARTING";
    public static final String STARTED="STARTED";
    public static final String STOPPING="STOPPING";
    public static final String RUNNING="RUNNING";
    
    private final Object _lock = new Object();
    private final int __FAILED = -1, __STOPPED = 0, __STARTING = 1, __STARTED = 2, __STOPPING = 3;
    private volatile int _state = __STOPPED;
    protected LifeCycle.Listener[] _listeners;

    protected void doStart() throws Exception
    {
    }

    protected void doStop() throws Exception
    {
    }

    public final void start() throws Exception
    {
        synchronized (_lock)
        {
            try
            {
                if (_state == __STARTED || _state == __STARTING)
                    return;
                setStarting();
                doStart();
                setStarted();
            }
            catch (Exception e)
            {
                setFailed(e);
                throw e;
            }
            catch (Error e)
            {
                setFailed(e);
                throw e;
            }
        }
    }

    public final void stop() throws Exception
    {
        synchronized (_lock)
        {
            try
            {
                if (_state == __STOPPING || _state == __STOPPED)
                    return;
                setStopping();
                doStop();
                setStopped();
            }
            catch (Exception e)
            {
                setFailed(e);
                throw e;
            }
            catch (Error e)
            {
                setFailed(e);
                throw e;
            }
        }
    }

    public boolean isRunning()
    {
        return _state == __STARTED || _state == __STARTING;
    }

    public boolean isStarted()
    {
        return _state == __STARTED;
    }

    public boolean isStarting()
    {
        return _state == __STARTING;
    }

    public boolean isStopping()
    {
        return _state == __STOPPING;
    }

    public boolean isStopped()
    {
        return _state == __STOPPED;
    }

    public boolean isFailed()
    {
        return _state == __FAILED;
    }

    public void addLifeCycleListener(LifeCycle.Listener listener)
    {
        _listeners = (LifeCycle.Listener[])LazyList.addToArray(_listeners,listener,LifeCycle.Listener.class);
    }

    public void removeLifeCycleListener(LifeCycle.Listener listener)
    {
        LazyList.removeFromArray(_listeners,listener);
    }
    
    public String getState()
    {
        switch(_state)
        {
            case __FAILED: return FAILED;
            case __STARTING: return STARTING;
            case __STARTED: return STARTED;
            case __STOPPING: return STOPPING;
            case __STOPPED: return STOPPED;
        }
        return null;
    }

    private void setStarted()
    {
        Log.debug(STARTED+" {}",this);
        _state = __STARTED;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleStarted(this);
            }
        }
    }

    private void setStarting()
    {
        Log.debug("Starting {}",this);
        _state = __STARTING;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleStarting(this);
            }
        }
    }

    private void setStopping()
    {
        _state = __STOPPING;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleStopping(this);
            }
        }
    }

    private void setStopped()
    {
        Log.debug(STOPPED+" {}",this);
        _state = __STOPPED;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleStopped(this);
            }
        }
    }

    private void setFailed(Throwable th)
    {
        Log.warn(FAILED+" " + this+": "+th);
        Log.debug(th);
        _state = __FAILED;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleFailure(this,th);
            }
        }
    }

    
}

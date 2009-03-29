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
    private final Object _lock = new Object();
    private final int FAILED = -1, STOPPED = 0, STARTING = 1, STARTED = 2, STOPPING = 3;
    private volatile int _state = STOPPED;
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
                if (_state == STARTED || _state == STARTING)
                    return;
                setStarting();
                doStart();
                Log.debug("started {}",this);
                setStarted();
            }
            catch (Exception e)
            {
                Log.warn("failed " + this,e);
                setFailed(e);
                throw e;
            }
            catch (Error e)
            {
                Log.warn("failed " + this,e);
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
                if (_state == STOPPING || _state == STOPPED)
                    return;
                setStopping();
                doStop();
                Log.debug("stopped {}",this);
                setStopped();
            }
            catch (Exception e)
            {
                Log.warn("failed " + this,e);
                setFailed(e);
                throw e;
            }
            catch (Error e)
            {
                Log.warn("failed " + this,e);
                setFailed(e);
                throw e;
            }
        }
    }

    public boolean isRunning()
    {
        return _state == STARTED || _state == STARTING;
    }

    public boolean isStarted()
    {
        return _state == STARTED;
    }

    public boolean isStarting()
    {
        return _state == STARTING;
    }

    public boolean isStopping()
    {
        return _state == STOPPING;
    }

    public boolean isStopped()
    {
        return _state == STOPPED;
    }

    public boolean isFailed()
    {
        return _state == FAILED;
    }

    public void addLifeCycleListener(LifeCycle.Listener listener)
    {
        _listeners = (LifeCycle.Listener[])LazyList.addToArray(_listeners,listener,LifeCycle.Listener.class);
    }

    public void removeLifeCycleListener(LifeCycle.Listener listener)
    {
        LazyList.removeFromArray(_listeners,listener);
    }

    private void setStarted()
    {
        _state = STARTED;
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
        _state = STARTING;
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
        _state = STOPPING;
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
        _state = STOPPED;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleStopped(this);
            }
        }
    }

    private void setFailed(Throwable error)
    {
        _state = FAILED;
        if (_listeners != null)
        {
            for (int i = 0; i < _listeners.length; i++)
            {
                _listeners[i].lifeCycleFailure(this,error);
            }
        }
    }

}

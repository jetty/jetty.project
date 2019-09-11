//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.component;

import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.Uptime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Basic implementation of the life cycle interface for components.
 */
@ManagedObject("Abstract Implementation of LifeCycle")
public abstract class AbstractLifeCycle implements LifeCycle
{
    private static final Logger LOG = Log.getLogger(AbstractLifeCycle.class);

    enum State
    {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING,
        FAILED
    }

    public static final String STOPPED = State.STOPPED.toString();
    public static final String FAILED = State.FAILED.toString();
    public static final String STARTING = State.STARTING.toString();
    public static final String STARTED = State.STARTED.toString();
    public static final String STOPPING = State.STOPPING.toString();

    private final CopyOnWriteArrayList<LifeCycle.Listener> _listeners = new CopyOnWriteArrayList<LifeCycle.Listener>();
    private final Object _lock = new Object();
    private volatile State _state = State.STOPPED;
    private long _stopTimeout = 30000;

    /**
     * Method to override to start the lifecycle
     * @throws StopException If thrown, the lifecycle will immediately be stopped.
     * @throws Exception If there was a problem starting. Will cause a transition to FAILED state
     */
    protected void doStart() throws Exception
    {
    }

    /**
     * Method to override to stop the lifecycle
     * @throws Exception If there was a problem stopping. Will cause a transition to FAILED state
     */
    protected void doStop() throws Exception
    {
    }

    @Override
    public final void start() throws Exception
    {
        synchronized (_lock)
        {
            try
            {
                switch (_state)
                {
                    case STARTED:
                        return;

                    case STARTING:
                    case STOPPING:
                        throw new IllegalStateException(getState());

                    default:
                        try
                        {
                            setStarting();
                            doStart();
                            setStarted();
                        }
                        catch (StopException e)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug(e);
                            setStopping();
                            doStop();
                            setStopped();
                        }
                }
            }
            catch (Throwable e)
            {
                setFailed(e);
                throw e;
            }
        }
    }

    @Override
    public final void stop() throws Exception
    {
        synchronized (_lock)
        {
            try
            {
                switch (_state)
                {
                    case STOPPED:
                        return;

                    case STARTING:
                    case STOPPING:
                        throw new IllegalStateException(getState());
                        
                    default:
                        setStopping();
                        doStop();
                        setStopped();
                }
            }
            catch (Throwable e)
            {
                setFailed(e);
                throw e;
            }
        }
    }

    @Override
    public boolean isRunning()
    {
        final State state = _state;
        switch (state)
        {
            case STARTED:
            case STARTING:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isStarted()
    {
        return _state == State.STARTED;
    }

    @Override
    public boolean isStarting()
    {
        return _state == State.STARTING;
    }

    @Override
    public boolean isStopping()
    {
        return _state == State.STOPPING;
    }

    @Override
    public boolean isStopped()
    {
        return _state == State.STOPPED;
    }

    @Override
    public boolean isFailed()
    {
        return _state == State.FAILED;
    }

    @Override
    public void addLifeCycleListener(LifeCycle.Listener listener)
    {
        _listeners.add(listener);
    }

    @Override
    public void removeLifeCycleListener(LifeCycle.Listener listener)
    {
        _listeners.remove(listener);
    }

    @ManagedAttribute(value = "Lifecycle State for this instance", readonly = true)
    public String getState()
    {
        return _state.toString();
    }

    public static String getState(LifeCycle lc)
    {
        if (lc instanceof AbstractLifeCycle)
            return ((AbstractLifeCycle)lc)._state.toString();
        if (lc.isStarting())
            return State.STARTING.toString();
        if (lc.isStarted())
            return State.STARTED.toString();
        if (lc.isStopping())
            return State.STOPPING.toString();
        if (lc.isStopped())
            return State.STOPPED.toString();
        return State.FAILED.toString();
    }

    private void setStarted()
    {
        if (_state == State.STARTING)
        {
            _state = State.STARTED;
            if (LOG.isDebugEnabled())
                LOG.debug("STARTED @{}ms {}", Uptime.getUptime(), this);
            for (Listener listener : _listeners)
                listener.lifeCycleStarted(this);
        }
    }

    private void setStarting()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("STARTING {}", this);
        _state = State.STARTING;
        for (Listener listener : _listeners)
            listener.lifeCycleStarting(this);
    }

    private void setStopping()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("STOPPING {}", this);
        _state = State.STOPPING;
        for (Listener listener : _listeners)
            listener.lifeCycleStopping(this);
    }

    private void setStopped()
    {
        if (_state == State.STOPPING)
        {
            _state = State.STOPPED;
            if (LOG.isDebugEnabled())
                LOG.debug("STOPPED {}", this);
            for (Listener listener : _listeners)
            {
                listener.lifeCycleStopped(this);
            }
        }
    }

    private void setFailed(Throwable th)
    {
        _state = State.FAILED;
        if (LOG.isDebugEnabled())
            LOG.warn("FAILED " + this + ": " + th, th);
        for (Listener listener : _listeners)
        {
            listener.lifeCycleFailure(this, th);
        }
    }

    @ManagedAttribute(value = "The stop timeout in milliseconds")
    public long getStopTimeout()
    {
        return _stopTimeout;
    }

    public void setStopTimeout(long stopTimeout)
    {
        this._stopTimeout = stopTimeout;
    }

    public abstract static class AbstractLifeCycleListener implements LifeCycle.Listener
    {
        @Override
        public void lifeCycleFailure(LifeCycle event, Throwable cause)
        {
        }

        @Override
        public void lifeCycleStarted(LifeCycle event)
        {
        }

        @Override
        public void lifeCycleStarting(LifeCycle event)
        {
        }

        @Override
        public void lifeCycleStopped(LifeCycle event)
        {
        }

        @Override
        public void lifeCycleStopping(LifeCycle event)
        {
        }
    }

    @Override
    public String toString()
    {
        Class<?> clazz = getClass();
        String name = clazz.getSimpleName();
        if ((name == null || name.length() == 0) && clazz.getSuperclass() != null)
        {
            clazz = clazz.getSuperclass();
            name = clazz.getSimpleName();
        }
        return String.format("%s@%x{%s}", name, hashCode(), getState());
    }

    /**
     * An exception, which if thrown by doStart will immediately stop the component
     */
    public class StopException extends RuntimeException
    {}
}

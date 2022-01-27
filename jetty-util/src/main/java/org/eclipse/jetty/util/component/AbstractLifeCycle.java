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

package org.eclipse.jetty.util.component;

import java.util.Collection;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Uptime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic implementation of the life cycle interface for components.
 */
@ManagedObject("Abstract Implementation of LifeCycle")
public abstract class AbstractLifeCycle implements LifeCycle
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLifeCycle.class);

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

    private final List<EventListener> _eventListener = new CopyOnWriteArrayList<>();
    private final AutoLock _lock = new AutoLock();
    private volatile State _state = State.STOPPED;

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
        try (AutoLock l = _lock.lock())
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
                                LOG.debug("Unable to stop", e);
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
        try (AutoLock l = _lock.lock())
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

    public List<EventListener> getEventListeners()
    {
        return _eventListener;
    }

    public void setEventListeners(Collection<EventListener> eventListeners)
    {
        for (EventListener l : _eventListener)
        {
            if (!eventListeners.contains(l))
                removeEventListener(l);
        }

        for (EventListener l : eventListeners)
        {
            if (!_eventListener.contains(l))
                addEventListener(l);
        }
    }

    @Override
    public boolean addEventListener(EventListener listener)
    {
        if (_eventListener.contains(listener))
            return false;
        _eventListener.add(listener);
        return true;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        return _eventListener.remove(listener);
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
            for (EventListener listener : _eventListener)
                if (listener instanceof Listener)
                    ((Listener)listener).lifeCycleStarted(this);
        }
    }

    private void setStarting()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("STARTING {}", this);
        _state = State.STARTING;
        for (EventListener listener : _eventListener)
            if (listener instanceof Listener)
                ((Listener)listener).lifeCycleStarting(this);
    }

    private void setStopping()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("STOPPING {}", this);
        _state = State.STOPPING;
        for (EventListener listener : _eventListener)
            if (listener instanceof Listener)
                ((Listener)listener).lifeCycleStopping(this);
    }

    private void setStopped()
    {
        if (_state == State.STOPPING)
        {
            _state = State.STOPPED;
            if (LOG.isDebugEnabled())
                LOG.debug("STOPPED {}", this);
            for (EventListener listener : _eventListener)
                if (listener instanceof Listener)
                    ((Listener)listener).lifeCycleStopped(this);
        }
    }

    private void setFailed(Throwable th)
    {
        _state = State.FAILED;
        if (LOG.isDebugEnabled())
            LOG.warn("FAILED {}: {}", this, th, th);
        for (EventListener listener : _eventListener)
        {
            if (listener instanceof Listener)
                ((Listener)listener).lifeCycleFailure(this, th);
        }
    }

    /**
     * @deprecated this class is redundant now that {@link LifeCycle.Listener} has default methods.
     */
    @Deprecated
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
        String name = getClass().getSimpleName();
        if (StringUtil.isBlank(name) && getClass().getSuperclass() != null)
            name = getClass().getSuperclass().getSimpleName();
        return String.format("%s@%x{%s}", name, hashCode(), getState());
    }

    /**
     * An exception, which if thrown by doStart will immediately stop the component
     */
    public class StopException extends RuntimeException
    {}
}

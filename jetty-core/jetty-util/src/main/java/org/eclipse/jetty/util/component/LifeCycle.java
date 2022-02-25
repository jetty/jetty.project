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

import java.util.EventListener;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

/**
 * The lifecycle interface for generic components.
 * <br>
 * Classes implementing this interface have a defined life cycle
 * defined by the methods of this interface.
 */
@ManagedObject("Lifecycle Interface for startable components")
public interface LifeCycle
{
    /**
     * Starts the component.
     *
     * @throws Exception If the component fails to start
     * @see #isStarted()
     * @see #stop()
     * @see #isFailed()
     */
    @ManagedOperation(value = "Starts the instance", impact = "ACTION")
    void start()
        throws Exception;

    /**
     * Utility to start an object if it is a LifeCycle and to convert
     * any exception thrown to a {@link RuntimeException}
     *
     * @param object The instance to start.
     * @throws RuntimeException if the call to start throws an exception.
     */
    static void start(Object object)
    {
        if (object instanceof LifeCycle)
        {
            try
            {
                ((LifeCycle)object).start();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Stops the component.
     * The component may wait for current activities to complete
     * normally, but it can be interrupted.
     *
     * @throws Exception If the component fails to stop
     * @see #isStopped()
     * @see #start()
     * @see #isFailed()
     */
    @ManagedOperation(value = "Stops the instance", impact = "ACTION")
    void stop()
        throws Exception;

    /**
     * Utility to stop an object if it is a LifeCycle and to convert
     * any exception thrown to a {@link RuntimeException}
     *
     * @param object The instance to stop.
     * @throws RuntimeException if the call to stop throws an exception.
     */
    static void stop(Object object)
    {
        if (object instanceof LifeCycle)
        {
            try
            {
                ((LifeCycle)object).stop();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @return true if the component is starting or has been started.
     */
    boolean isRunning();

    /**
     * @return true if the component has been started.
     * @see #start()
     * @see #isStarting()
     */
    boolean isStarted();

    /**
     * @return true if the component is starting.
     * @see #isStarted()
     */
    boolean isStarting();

    /**
     * @return true if the component is stopping.
     * @see #isStopped()
     */
    boolean isStopping();

    /**
     * @return true if the component has been stopped.
     * @see #stop()
     * @see #isStopping()
     */
    boolean isStopped();

    /**
     * @return true if the component has failed to start or has failed to stop.
     */
    boolean isFailed();

    boolean addEventListener(EventListener listener);

    boolean removeEventListener(EventListener listener);

    /**
     * Listener.
     * A listener for Lifecycle events.
     */
    interface Listener extends EventListener
    {
        default void lifeCycleStarting(LifeCycle event)
        {
        }

        default void lifeCycleStarted(LifeCycle event)
        {
        }

        default void lifeCycleFailure(LifeCycle event, Throwable cause)
        {
        }

        default void lifeCycleStopping(LifeCycle event)
        {
        }

        default void lifeCycleStopped(LifeCycle event)
        {
        }
    }
}

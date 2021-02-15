//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

    void addLifeCycleListener(LifeCycle.Listener listener);

    void removeLifeCycleListener(LifeCycle.Listener listener);

    /**
     * Listener.
     * A listener for Lifecycle events.
     */
    interface Listener extends EventListener
    {
        void lifeCycleStarting(LifeCycle event);

        void lifeCycleStarted(LifeCycle event);

        void lifeCycleFailure(LifeCycle event, Throwable cause);

        void lifeCycleStopping(LifeCycle event);

        void lifeCycleStopped(LifeCycle event);
    }

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
}

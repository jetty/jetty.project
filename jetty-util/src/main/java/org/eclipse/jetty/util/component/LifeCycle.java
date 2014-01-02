//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

/* ------------------------------------------------------------ */
/**
 * The lifecycle interface for generic components.
 * <br />
 * Classes implementing this interface have a defined life cycle
 * defined by the methods of this interface.
 *
 * 
 */
public interface LifeCycle
{
    /* ------------------------------------------------------------ */
    /**
     * Starts the component.
     * @throws Exception If the component fails to start
     * @see #isStarted()
     * @see #stop()
     * @see #isFailed()
     */
    public void start()
        throws Exception;

    /* ------------------------------------------------------------ */
    /**
     * Stops the component.
     * The component may wait for current activities to complete
     * normally, but it can be interrupted.
     * @exception Exception If the component fails to stop
     * @see #isStopped()
     * @see #start()
     * @see #isFailed()
     */
    public void stop()
        throws Exception;

    /* ------------------------------------------------------------ */
    /**
     * @return true if the component is starting or has been started.
     */
    public boolean isRunning();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the component has been started.
     * @see #start()
     * @see #isStarting()
     */
    public boolean isStarted();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the component is starting.
     * @see #isStarted()
     */
    public boolean isStarting();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the component is stopping.
     * @see #isStopped()
     */
    public boolean isStopping();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the component has been stopped.
     * @see #stop()
     * @see #isStopping()
     */
    public boolean isStopped();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the component has failed to start or has failed to stop.
     */
    public boolean isFailed();
    
    /* ------------------------------------------------------------ */
    public void addLifeCycleListener(LifeCycle.Listener listener);

    /* ------------------------------------------------------------ */
    public void removeLifeCycleListener(LifeCycle.Listener listener);
    

    /* ------------------------------------------------------------ */
    /** Listener.
     * A listener for Lifecycle events.
     */
    public interface Listener extends EventListener
    {
        public void lifeCycleStarting(LifeCycle event);
        public void lifeCycleStarted(LifeCycle event);
        public void lifeCycleFailure(LifeCycle event,Throwable cause);
        public void lifeCycleStopping(LifeCycle event);
        public void lifeCycleStopped(LifeCycle event);
    }
}

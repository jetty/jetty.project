// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------ */
/**
 * ShutdownThread is a shutdown hook thread implemented as 
 * singleton that maintains a list of lifecycle instances
 * that are registered with it and provides ability to stop
 * these lifecycles upon shutdown of the Java Virtual Machine 
 */
public class ShutdownThread extends Thread
{
    private static final ShutdownThread _thread = new ShutdownThread();

    private final List<LifeCycle> _lifeCycles = new CopyOnWriteArrayList<LifeCycle>();

    /* ------------------------------------------------------------ */
    /**
     * Default constructor for the singleton
     * 
     * Registers the instance as shutdown hook with the Java Runtime
     */
    private ShutdownThread()
    {
        Runtime.getRuntime().addShutdownHook(this);
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the instance of the singleton
     * 
     * @return the singleton instance of the {@link ShutdownThread}
     */
    public static ShutdownThread getInstance()
    {
        return _thread;
    }

    /* ------------------------------------------------------------ */
    public static synchronized void register(LifeCycle... lifeCycles)
    {
        _thread._lifeCycles.addAll(Arrays.asList(lifeCycles));
    }

    /* ------------------------------------------------------------ */
    public static synchronized void register(int index, LifeCycle... lifeCycles)
    {
        _thread._lifeCycles.addAll(index,Arrays.asList(lifeCycles));
    }
    
    /* ------------------------------------------------------------ */
    public static synchronized void deregister(LifeCycle lifeCycle)
    {
        _thread._lifeCycles.remove(lifeCycle);
    }

    /* ------------------------------------------------------------ */
    public void run()
    {
        for (LifeCycle lifeCycle : _thread._lifeCycles)
        {
            try
            {
                lifeCycle.stop();
                Log.debug("Stopped " + lifeCycle);
            }
            catch (Exception ex)
            {
                Log.debug(ex);
            }
        }
    }

    /* ------------------------------------------------------------ */
    protected Object readResolve()
        throws ObjectStreamException
    {
        return _thread;
    }
}
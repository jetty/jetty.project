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

package org.eclipse.jetty.monitor.jmx;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

/* ------------------------------------------------------------ */
/**
 * MonitorTask
 * 
 * Invokes polling of the JMX server for the MBean attribute values
 * through executing timer task scheduled using java.util.Timer 
 * at specified poll interval following a specified delay.
 */
public class MonitorTask extends TimerTask
{
    private static final Logger LOG = Log.getLogger(MonitorTask.class);

    private static Timer __timer = new Timer(true);
    private static ThreadPool _callback = new ExecutorThreadPool(4,64,60,TimeUnit.SECONDS);;
    private static Map<String,TimerTask> __tasks  = new HashMap<String,TimerTask>();

    private final MonitorAction _action;
    
    /* ------------------------------------------------------------ */
    /**
     * Creates new instance of MonitorTask 
     * 
     * @param action instance of MonitorAction to use
     */
    private MonitorTask(MonitorAction action)
    {
        _action = action;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Schedule new timer task for specified monitor action
     * 
     * @param action monitor action
     */
    public static void schedule(MonitorAction action)
    {
        TimerTask task = new MonitorTask(action);
        __timer.scheduleAtFixedRate(task,
                                    action.getPollDelay(),
                                    action.getPollInterval());
        
        __tasks.put(action.getID(), task);
   }
    
    /* ------------------------------------------------------------ */
    /**
     * Cancel timer task for specified monitor action
     * 
     * @param action monitor action
     */
    public static void cancel(MonitorAction action)
    {
        TimerTask task = __tasks.remove(action.getID());
        if (task != null)
            task.cancel();
    }

    /* ------------------------------------------------------------ */
    /**
     * This method is invoked when poll interval has elapsed
     * to check if the event trigger conditions are satisfied
     * in order to fire event.
     *
     * @see java.util.TimerTask#run()
     */
    @Override
    public final void run()
    {
        final long timestamp = System.currentTimeMillis();
        final EventTrigger trigger = _action.getTrigger();

        _callback.execute(new Runnable() {
            public void run()
            {
                try
                {
                    if(trigger.match(timestamp))
                        _action.doExecute(timestamp);
                }
                catch (Exception ex)
                {
                    LOG.debug(ex);
                }
            }
        });
    }
}

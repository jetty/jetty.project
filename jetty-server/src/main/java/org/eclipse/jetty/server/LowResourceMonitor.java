//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.concurrent.Executor;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ThreadPool;


/**
 *
 * A monitor for low resources, low resources can be detected by:
 * <ul>
 * <li>{@link ThreadPool#isLowOnThreads()} if {@link Connector#getExecutor()} is
 * an instance of {@link ThreadPool} and {@link #setMonitorThreads(boolean)} is true.</li>
 * <li>If {@link #setMaxMemory(long)} is non zero then low resources is detected if the JVMs
 * {@link Runtime} instance has {@link Runtime#totalMemory()} minus {@link Runtime#freeMemory()}
 * greater than {@link #getMaxMemory()}</li>
 * <li>If {@link #setMaxConnections(int)} is non zero then low resources is detected if the total number
 * of connections exceeds {@link #getMaxConnections()}.  This feature is deprecated and replaced by
 * {@link ConnectionLimit}</li>
 * </ul>
 *
 */
@ManagedObject ("Monitor for low resource conditions and activate a low resource mode if detected")
public class LowResourceMonitor extends AbstractResourceMonitor
{
    private static final Logger LOG = Log.getLogger(LowResourceMonitor.class);

    private boolean _monitorThreads=true;
    private int _maxConnections;
    private long _maxMemory;

    public LowResourceMonitor(@Name("server") Server server)
    {
        super(server);
    }


    @ManagedAttribute("True if low available threads status is monitored")
    public boolean getMonitorThreads()
    {
        return _monitorThreads;
    }

    /**
     * @param monitorThreads If true, check connectors executors to see if they are
     * {@link ThreadPool} instances that are low on threads.
     */
    public void setMonitorThreads(boolean monitorThreads)
    {
        _monitorThreads = monitorThreads;
    }

    /**
     * @return The maximum connections allowed for the monitored connectors before low resource handling is activated
     * @deprecated Replaced by ConnectionLimit
     */
    @ManagedAttribute("The maximum connections allowed for the monitored connectors before low resource handling is activated")
    @Deprecated
    public int getMaxConnections()
    {
        return _maxConnections;
    }

    /**
     * @param maxConnections The maximum connections before low resources state is triggered
     * @deprecated Replaced by ConnectionLimit
     */
    @Deprecated
    public void setMaxConnections(int maxConnections)
    {
        if (maxConnections>0)
            LOG.warn("LowResourceMonitor.setMaxConnections is deprecated. Use ConnectionLimit.");
        _maxConnections = maxConnections;
    }


    @ManagedAttribute("The maximum memory (in bytes) that can be used before low resources is triggered.  Memory used is calculated as (totalMemory-freeMemory).")
    public long getMaxMemory()
    {
        return _maxMemory;
    }

    /**
     * @param maxMemoryBytes The maximum memory in bytes in use before low resources is triggered.
     */
    public void setMaxMemory(long maxMemoryBytes)
    {
        _maxMemory = maxMemoryBytes;
    }

    protected void monitor()
    {
        String reasons=null;
        String cause="";
        int connections=0;

        ThreadPool serverThreads = _server.getThreadPool();
        if (_monitorThreads && serverThreads.isLowOnThreads())
        {
            reasons=low(reasons,"Server low on threads: "+serverThreads);
            cause+="S";
        }

        for(Connector connector : getMonitoredOrServerConnectors())
        {
            connections+=connector.getConnectedEndPoints().size();

            Executor executor = connector.getExecutor();
            if (executor instanceof ThreadPool && executor!=serverThreads)
            {
                ThreadPool connectorThreads=(ThreadPool)executor;
                if (_monitorThreads && connectorThreads.isLowOnThreads())
                {
                    reasons=low(reasons,"Connector low on threads: "+connectorThreads);
                    cause+="T";
                }
            }
        }

        if (_maxConnections>0 && connections>_maxConnections)
        {
            reasons=low(reasons,"Max Connections exceeded: "+connections+">"+_maxConnections);
            cause+="C";
        }

        long memory=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        if (_maxMemory>0 && memory>_maxMemory)
        {
            reasons=low(reasons,"Max memory exceeded: "+memory+">"+_maxMemory);
            cause+="M";
        }

        if (reasons!=null)
        {
            // Log the reasons if there is any change in the cause
            if (!cause.equals(getCause()))
            {
                LOG.warn("Low Resources: {}",reasons);
                setCause(cause);
            }

            // Enter low resources state?
            if (enableLowOnResources(false,true))
            {
                setLowResourcesReasons(reasons);
                setLowResourcesStarted(System.currentTimeMillis());
                setLowResources();
            }

            // Too long in low resources state?
            if ( getMaxLowResourcesTime()>0 && (System.currentTimeMillis()-getLowResourcesStarted())>getMaxLowResourcesTime())
                setLowResources();
        }
        else
        {
            if (enableLowOnResources(true,false))
            {
                LOG.info("Low Resources cleared");
                setLowResourcesReasons(null);
                setLowResourcesStarted(0);
                setCause(null);
                clearLowResources();
            }
        }
    }
}

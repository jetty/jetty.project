//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;


/**
 * A monitor for low resources
 * <p>
 * An instance of this class will monitor all the connectors of a server (or a set of connectors
 * configured with {@link #setMonitoredConnectors(Collection)}) for a low resources state.
 * <p>
 * Low resources can be detected by:
 * <ul>
 * <li>{@link ThreadPool#isLowOnThreads()} if {@link Connector#getExecutor()} is
 * an instance of {@link ThreadPool} and {@link #setMonitorThreads(boolean)} is true.<li>
 * <li>If {@link #setMaxMemory(long)} is non zero then low resources is detected if the JVMs
 * {@link Runtime} instance has {@link Runtime#totalMemory()} minus {@link Runtime#freeMemory()}
 * greater than {@link #getMaxMemory()}</li>
 * <li>If {@link #setMaxConnections(int)} is non zero then low resources is dected if the total number
 * of connections exceeds {@link #getMaxConnections()}</li>
 * </ul>
 * <p>
 * Once low resources state is detected, the cause is logged and all existing connections returned
 * by {@link Connector#getConnectedEndPoints()} have {@link EndPoint#setIdleTimeout(long)} set
 * to {@link #getLowResourcesIdleTimeout()}.  New connections are not affected, however if the low
 * resources state persists for more than {@link #getMaxLowResourcesTime()}, then the
 * {@link #getLowResourcesIdleTimeout()} to all connections again.  Once the low resources state is
 * cleared, the idle timeout is reset to the connector default given by {@link Connector#getIdleTimeout()}.
 */
@ManagedObject ("Monitor for low resource conditions and activate a low resource mode if detected")
public class LowResourceMonitor extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(LowResourceMonitor.class);
    private final Server _server;
    private Scheduler _scheduler;
    private Connector[] _monitoredConnectors;
    private int _period=1000;
    private int _maxConnections;
    private long _maxMemory;
    private int _lowResourcesIdleTimeout=1000;
    private int _maxLowResourcesTime=0;
    private boolean _monitorThreads=true;
    private final AtomicBoolean _low = new AtomicBoolean();
    private String _cause;
    private String _reasons;
    private long _lowStarted;

    private final Runnable _monitor = new Runnable()
    {
        @Override
        public void run()
        {
            if (isRunning())
            {
                monitor();
                _scheduler.schedule(_monitor,_period,TimeUnit.MILLISECONDS);
            }
        }
    };

    public LowResourceMonitor(@Name("server") Server server)
    {
        _server=server;
    }

    @ManagedAttribute("Are the monitored connectors low on resources?")
    public boolean isLowOnResources()
    {
        return _low.get();
    }

    @ManagedAttribute("The reason(s) the monitored connectors are low on resources")
    public String getLowResourcesReasons()
    {
        return _reasons;
    }

    @ManagedAttribute("Get the timestamp in ms since epoch that low resources state started")
    public long getLowResourcesStarted()
    {
        return _lowStarted;
    }

    @ManagedAttribute("The monitored connectors. If null then all server connectors are monitored")
    public Collection<Connector> getMonitoredConnectors()
    {
        if (_monitoredConnectors==null)
            return Collections.emptyList();
        return Arrays.asList(_monitoredConnectors);
    }

    /**
     * @param monitoredConnectors The collections of Connectors that should be monitored for low resources.
     */
    public void setMonitoredConnectors(Collection<Connector> monitoredConnectors)
    {
        if (monitoredConnectors==null || monitoredConnectors.size()==0)
            _monitoredConnectors=null;
        else
            _monitoredConnectors = monitoredConnectors.toArray(new Connector[monitoredConnectors.size()]);
    }

    @ManagedAttribute("The monitor period in ms")
    public int getPeriod()
    {
        return _period;
    }

    /**
     * @param periodMS The period in ms to monitor for low resources
     */
    public void setPeriod(int periodMS)
    {
        _period = periodMS;
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

    @ManagedAttribute("The maximum connections allowed for the monitored connectors before low resource handling is activated")
    public int getMaxConnections()
    {
        return _maxConnections;
    }

    /**
     * @param maxConnections The maximum connections before low resources state is triggered
     */
    public void setMaxConnections(int maxConnections)
    {
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

    @ManagedAttribute("The idletimeout in ms to apply to all existing connections when low resources is detected")
    public int getLowResourcesIdleTimeout()
    {
        return _lowResourcesIdleTimeout;
    }

    /**
     * @param lowResourcesIdleTimeoutMS The timeout in ms to apply to EndPoints when in the low resources state.
     */
    public void setLowResourcesIdleTimeout(int lowResourcesIdleTimeoutMS)
    {
        _lowResourcesIdleTimeout = lowResourcesIdleTimeoutMS;
    }

    @ManagedAttribute("The maximum time in ms that low resources condition can persist before lowResourcesIdleTimeout is applied to new connections as well as existing connections")
    public int getMaxLowResourcesTime()
    {
        return _maxLowResourcesTime;
    }

    /**
     * @param maxLowResourcesTimeMS The time in milliseconds that a low resource state can persist before the low resource idle timeout is reapplied to all connections
     */
    public void setMaxLowResourcesTime(int maxLowResourcesTimeMS)
    {
        _maxLowResourcesTime = maxLowResourcesTimeMS;
    }

    @Override
    protected void doStart() throws Exception
    {
        _scheduler = _server.getBean(Scheduler.class);

        if (_scheduler==null)
        {
            _scheduler=new LRMScheduler();
            _scheduler.start();
        }
        super.doStart();

        _scheduler.schedule(_monitor,_period,TimeUnit.MILLISECONDS);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (_scheduler instanceof LRMScheduler)
            _scheduler.stop();
        super.doStop();
    }

    protected Connector[] getMonitoredOrServerConnectors()
    {
        if (_monitoredConnectors!=null && _monitoredConnectors.length>0)
            return _monitoredConnectors;
        return _server.getConnectors();
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
            if (!cause.equals(_cause))
            {
                LOG.warn("Low Resources: {}",reasons);
                _cause=cause;
            }

            // Enter low resources state?
            if (_low.compareAndSet(false,true))
            {
                _reasons=reasons;
                _lowStarted=System.currentTimeMillis();
                setLowResources();
            }

            // Too long in low resources state?
            if (_maxLowResourcesTime>0 && (System.currentTimeMillis()-_lowStarted)>_maxLowResourcesTime)
                setLowResources();
        }
        else
        {
            if (_low.compareAndSet(true,false))
            {
                LOG.info("Low Resources cleared");
                _reasons=null;
                _lowStarted=0;
                _cause=null;
                clearLowResources();
            }
        }
    }

    protected void setLowResources()
    {
        for(Connector connector : getMonitoredOrServerConnectors())
        {
            for (EndPoint endPoint : connector.getConnectedEndPoints())
                endPoint.setIdleTimeout(_lowResourcesIdleTimeout);
        }
    }

    protected void clearLowResources()
    {
        for(Connector connector : getMonitoredOrServerConnectors())
        {
            for (EndPoint endPoint : connector.getConnectedEndPoints())
                endPoint.setIdleTimeout(connector.getIdleTimeout());
        }
    }

    private String low(String reasons, String newReason)
    {
        if (reasons==null)
            return newReason;
        return reasons+", "+newReason;
    }


    private static class LRMScheduler extends ScheduledExecutorScheduler
    {
    }
}

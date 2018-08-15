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

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * Base class to monitor resources, you have to override this class and implement the {@link #monitor()} method.
 * The method is called on a regular basis {@link #getPeriod()} in Ms
 * </p>
 * <p>
 * An instance of this class will monitor all the connectors of a server (or a set of connectors
 * configured with {@link #setMonitoredConnectors(Collection)}) for a low resources state.
 * </p>
 * <p>
 * Once low resources state is detected
 * </p>
 * <ul>
 *   <li>the cause can be logged</li>
 *   <li>all existing connections returned by {@link Connector#getConnectedEndPoints()} have {@link EndPoint#setIdleTimeout(long)} set
 *  to {@link #getLowResourcesIdleTimeout()}.</li>
 *  <li>New connections are not affected, however if the low resources state persists for more than {@link #getMaxLowResourcesTime()},
 *  then the {@link #getLowResourcesIdleTimeout()} to all connections again.</li>
 *  <li>Once the low resources state is
 *  cleared, the idle timeout is reset to the connector default given by {@link Connector#getIdleTimeout()}.</li>
 *  <li>If {@link #setAcceptingInLowResources(boolean)} is set to false (Default is true), then no new connections
 *  are accepted when in low resources state.</li>
 * </ul>
 * <p>
 *  In your implementation of the {@link #monitor()} method, you can achieve this with the following code
 * </p>
 * <pre>
 *  // you want to ensure you can change the status from false to true
 *  if (enableLowOnResources(false,true))
 *  {
 *      // log whatever you want
 *      LOG.info( "" );
 *      // configure a reason
 *      setLowResourcesReasons("directory not empty");
 *      // a flag of your convenience
 *      setCause("")
 *      // configure the start time of the low resources status
 *      setLowResourcesStarted(System.currentTimeMillis());
 *      // enable the low resources mode
 *      setLowResources();
 *  }
 *  </pre>
 *  <p>
 *  To disable the low resources status, you can achieve this with the following code
 *  </p>
 *  <pre>
 *  // you want to ensure you can change the status from true to false
 *  if (enableLowOnResources(true,false))
 *  {
 *      // log whatever you want
 *      LOG.info( "" );
 *      // remove previous reason
 *      setLowResourcesReasons(null);
 *      // reset start time
 *      setLowResourcesStarted(0);
 *      // disable flag
 *      setCause(null);
 *      // disable the low resources mode
 *      clearLowResources();
 *  }
 *  </pre>
 */
@ManagedObject("AbstractResourceMonitor")
public abstract class AbstractResourceMonitor  extends AbstractLifeCycle
{

    protected final Server _server;
    private Scheduler _scheduler;
    private Connector[] _monitoredConnectors;
    private Set<AbstractConnector> _acceptingConnectors = new HashSet<>();
    private int _period=1000;


    private int _lowResourcesIdleTimeout=1000;
    private int _maxLowResourcesTime=0;

    private final AtomicBoolean _low = new AtomicBoolean();

    private String _cause;
    private String _reasons;

    private long _lowStarted;
    private boolean _acceptingInLowResources = true;

    private final Runnable _monitor = new Runnable()
    {
        @Override
        public void run()
        {
            if (isRunning())
            {
                monitor();
                _scheduler.schedule( _monitor, _period, TimeUnit.MILLISECONDS);
            }
        }
    };


    public AbstractResourceMonitor(@Name("server") Server server)
    {
        _server=server;
    }

    @ManagedAttribute("The cause the monitored connectors are low on resources")
    public String getCause()
    {
        return _cause;
    }

    protected void setCause(String cause)
    {
        _cause = cause;
    }

    @ManagedAttribute("Are the monitored connectors low on resources?")
    public boolean isLowOnResources()
    {
        return _low.get();
    }

    protected boolean enableLowOnResources(boolean expectedValue, boolean newValue)
    {
        return _low.compareAndSet(expectedValue, newValue);
    }

    @ManagedAttribute("The reason(s) the monitored connectors are low on resources")
    public String getLowResourcesReasons()
    {
        return _reasons;
    }

    protected void setLowResourcesReasons(String reasons)
    {
        _reasons = reasons;
    }

    @ManagedAttribute("Get the timestamp in ms since epoch that low resources state started")
    public long getLowResourcesStarted()
    {
        return _lowStarted;
    }

    public void setLowResourcesStarted(long lowStarted)
    {
        _lowStarted = lowStarted;
    }

    @ManagedAttribute("The monitored connectors. If null then all server connectors are monitored")
    public Collection<Connector> getMonitoredConnectors()
    {
        if (_monitoredConnectors==null)
            return Collections.emptyList();
        return Arrays.asList( _monitoredConnectors);
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

    @ManagedAttribute("If false, new connections are not accepted while in low resources")
    public boolean isAcceptingInLowResources()
    {
        return _acceptingInLowResources;
    }

    public void setAcceptingInLowResources(boolean acceptingInLowResources)
    {
        _acceptingInLowResources = acceptingInLowResources;
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
            _scheduler=new AbstractResourceMonitor.LRMScheduler();
            _scheduler.start();
        }
        super.doStart();

        _scheduler.schedule(_monitor,_period,TimeUnit.MILLISECONDS);
    }

    @Override
    protected void doStop() throws Exception
    {
        if (_scheduler instanceof AbstractResourceMonitor.LRMScheduler )
            _scheduler.stop();
        super.doStop();
    }

    protected Connector[] getMonitoredOrServerConnectors()
    {
        if (_monitoredConnectors!=null && _monitoredConnectors.length>0)
            return _monitoredConnectors;
        return _server.getConnectors();
    }

    protected abstract void monitor();

    protected void setLowResources()
    {
        for(Connector connector : getMonitoredOrServerConnectors())
        {
            if (connector instanceof AbstractConnector)
            {
                AbstractConnector c = (AbstractConnector)connector;
                if (!isAcceptingInLowResources() && c.isAccepting())
                {
                    _acceptingConnectors.add(c);
                    c.setAccepting(false);
                }
            }

            for ( EndPoint endPoint : connector.getConnectedEndPoints())
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

        for (AbstractConnector connector : _acceptingConnectors)
        {
            connector.setAccepting(true);
        }
        _acceptingConnectors.clear();
    }

    protected String low(String reasons, String newReason)
    {
        if (reasons==null)
            return newReason;
        return reasons+", "+newReason;
    }


    private static class LRMScheduler extends ScheduledExecutorScheduler
    {
    }

}

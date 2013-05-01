//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;


/* ------------------------------------------------------------ */
/** A Connector.Listener that gathers Connector and Connections Statistics.
 * Adding an instance of this class as with {@link AbstractConnector#addBean(Object)} 
 * will register the listener with all connections accepted by that connector.
 */
@ManagedObject("Connector Statistics")
public class ConnectorStatistics extends AbstractLifeCycle implements Dumpable, Connection.Listener
{
    private final AtomicLong _startMillis = new AtomicLong(-1L);
    private final CounterStatistic _connectionStats = new CounterStatistic();
    private final SampleStatistic _messagesIn = new SampleStatistic();
    private final SampleStatistic _messagesOut = new SampleStatistic();
    private final SampleStatistic _connectionDurationStats = new SampleStatistic();

    @Override
    public void onOpened(Connection connection)
    {
        connectionOpened();
    }

    @Override
    public void onClosed(Connection connection)
    {
        connectionClosed(System.currentTimeMillis()-connection.getCreatedTimeStamp(),connection.getMessagesIn(),connection.getMessagesOut());
    }

    @ManagedAttribute("Total number of bytes received by this connector")
    public int getBytesIn()
    {
        // TODO
        return -1;
    }

    @ManagedAttribute("Total number of bytes sent by this connector")
    public int getBytesOut()
    {
        // TODO
        return -1;
    }

    @ManagedAttribute("Total number of connections seen by this connector")
    public int getConnections()
    {
        return (int)_connectionStats.getTotal();
    }

    @ManagedAttribute("Connection duraton maximum in ms")
    public long getConnectionsDurationMax()
    {
        return _connectionDurationStats.getMax();
    }

    @ManagedAttribute("Connection duraton mean in ms")
    public double getConnectionsDurationMean()
    {
        return _connectionDurationStats.getMean();
    }

    @ManagedAttribute("Connection duraton standard deviation")
    public double getConnectionsDurationStdDev()
    {
        return _connectionDurationStats.getStdDev();
    }

    @ManagedAttribute("Connection duraton total of all connections in ms")
    public long getConnectionsDurationTotal()
    {
        return _connectionDurationStats.getTotal();
    }

    @ManagedAttribute("Messages In for all connections")
    public int getMessagesIn()
    {
        return (int)_messagesIn.getTotal();
    }

    @ManagedAttribute("Messages In per connection maximum")
    public int getConnectionsMessagesInMax()
    {
        return (int)_messagesIn.getMax();
    }

    @ManagedAttribute("Messages In per connection mean")
    public double getConnectionsMessagesInMean()
    {
        return _messagesIn.getMean();
    }

    @ManagedAttribute("Messages In per connection standard deviation")
    public double getConnectionsMessagesInStdDev()
    {
        return _messagesIn.getStdDev();
    }

    @ManagedAttribute("Connections open")
    public int getConnectionsOpen()
    {
        return (int)_connectionStats.getCurrent();
    }

    @ManagedAttribute("Connections open maximum")
    public int getConnectionsOpenMax()
    {
        return (int)_connectionStats.getMax();
    }

    @ManagedAttribute("Messages Out for all connections")
    public int getMessagesOut()
    {
        return (int)_messagesIn.getTotal();
    }

    @ManagedAttribute("Connection statistics started ms since epoch")
    public long getStartedMillis()
    {
        long start = _startMillis.get();
        return start < 0 ? 0 : System.currentTimeMillis() - start;
    }

    @Override
    public void doStart()
    {
        reset();
    }

    @Override
    public void doStop()
    {
    }

    @ManagedOperation("Reset the statistics")
    public void reset()
    {
        _startMillis.set(System.currentTimeMillis());
        _messagesIn.reset();
        _messagesOut.reset();
        _connectionStats.reset();
        _connectionDurationStats.reset();
    }

    public void connectionOpened()
    {
        if (isStarted())
        {
            _connectionStats.increment();
        }
    }

    public void connectionUpgraded(int messagesIn, int messagesOut)
    {
        if (isStarted())
        {
            _messagesIn.set(messagesIn);
            _messagesOut.set(messagesOut);
        }
    }

    public void connectionClosed(long duration, int messagesIn, int messagesOut)
    {
        if (isStarted())
        {
            _messagesIn.set(messagesIn);
            _messagesOut.set(messagesOut);
            _connectionStats.decrement();
            _connectionDurationStats.set(duration);
        }
    }

    @Override
    @ManagedOperation("dump thread state")
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out,this);
        ContainerLifeCycle.dump(out,indent,Arrays.asList(new String[]{"connections="+_connectionStats,"duration="+_connectionDurationStats,"in="+_messagesIn,"out="+_messagesOut}));
    }
    
    public static void addToAllConnectors(Server server)
    {
        for (Connector connector : server.getConnectors())
        {
            if (connector instanceof Container)
             ((Container)connector).addBean(new ConnectorStatistics());
        }
    }
}

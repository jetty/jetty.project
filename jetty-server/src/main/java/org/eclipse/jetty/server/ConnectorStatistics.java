//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

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

    public int getBytesIn()
    {
        // TODO
        return -1;
    }

    public int getBytesOut()
    {
        // TODO
        return -1;
    }

    public int getConnections()
    {
        return (int)_connectionStats.getTotal();
    }

    public long getConnectionsDurationMax()
    {
        return _connectionDurationStats.getMax();
    }

    public double getConnectionsDurationMean()
    {
        return _connectionDurationStats.getMean();
    }

    public double getConnectionsDurationStdDev()
    {
        return _connectionDurationStats.getStdDev();
    }

    public long getConnectionsDurationTotal()
    {
        return _connectionDurationStats.getTotal();
    }

    public int getConnectionsMessagesInMax()
    {
        return (int)_messagesIn.getMax();
    }

    public double getConnectionsMessagesInMean()
    {
        return _messagesIn.getMean();
    }

    public double getConnectionsMessagesInStdDev()
    {
        return _messagesIn.getStdDev();
    }

    public int getConnectionsOpen()
    {
        return (int)_connectionStats.getCurrent();
    }

    public int getConnectionsOpenMax()
    {
        return (int)_connectionStats.getMax();
    }

    public int getMessagesIn()
    {
        return (int)_messagesIn.getTotal();
    }

    public int getMessagesOut()
    {
        return (int)_messagesIn.getTotal();
    }

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
}

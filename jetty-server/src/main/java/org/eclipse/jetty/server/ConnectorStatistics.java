// ========================================================================
// Copyright (c) 2004-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.server.Connector.Statistics;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

class ConnectorStatistics extends AbstractLifeCycle implements Statistics, Dumpable
{
    private final AtomicLong _startMillis = new AtomicLong(-1L);
    private final CounterStatistic _connectionStats = new CounterStatistic();
    private final SampleStatistic _messagesIn = new SampleStatistic();
    private final SampleStatistic _messagesOut = new SampleStatistic();
    private final SampleStatistic _connectionDurationStats = new SampleStatistic();

    @Override
    public int getBytesIn()
    {
        // TODO
        return -1;
    }

    @Override
    public int getBytesOut()
    {
        // TODO
        return -1;
    }

    @Override
    public int getConnections()
    {
        return (int)_connectionStats.getTotal();
    }

    @Override
    public long getConnectionsDurationMax()
    {
        return _connectionDurationStats.getMax();
    }

    @Override
    public double getConnectionsDurationMean()
    {
        return _connectionDurationStats.getMean();
    }

    @Override
    public double getConnectionsDurationStdDev()
    {
        return _connectionDurationStats.getStdDev();
    }

    @Override
    public long getConnectionsDurationTotal()
    {
        return _connectionDurationStats.getTotal();
    }

    @Override
    public int getConnectionsMessagesInMax()
    {
        return (int)_messagesIn.getMax();
    }

    @Override
    public double getConnectionsMessagesInMean()
    {
        return _messagesIn.getMean();
    }

    @Override
    public double getConnectionsMessagesInStdDev()
    {
        return _messagesIn.getStdDev();
    }

    @Override
    public int getConnectionsOpen()
    {
        return (int)_connectionStats.getCurrent();
    }

    @Override
    public int getConnectionsOpenMax()
    {
        return (int)_connectionStats.getMax();
    }

    @Override
    public int getMessagesIn()
    {
        return (int)_messagesIn.getTotal();
    }

    @Override
    public int getMessagesOut()
    {
        return (int)_messagesIn.getTotal();
    }

    @Override
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

    @Override
    public void reset()
    {
        _startMillis.set(System.currentTimeMillis());
        _messagesIn.reset();
        _messagesOut.reset();
        _connectionStats.reset();
        _connectionDurationStats.reset();
    }

    @Override
    public void connectionOpened()
    {
        if (isStarted())
        {
            _connectionStats.increment();
        }
    }

    @Override
    public void connectionUpgraded(long duration, int messagesIn, int messagesOut)
    {
        if (isStarted())
        {
            _messagesIn.set(messagesIn);
            _messagesOut.set(messagesOut);
        }
    }

    @Override
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
        return AggregateLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out,indent,Arrays.asList(new String[]{"connections="+_connectionStats,"duration="+_connectionDurationStats,"in="+_messagesIn,"out="+_messagesOut}));
    }
}

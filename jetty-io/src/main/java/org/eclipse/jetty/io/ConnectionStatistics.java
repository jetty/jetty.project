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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.RateCounter;
import org.eclipse.jetty.util.statistic.SampleStatistic;

/**
 * <p>A {@link Connection.Listener} that tracks connection statistics.</p>
 * <p>Adding an instance of this class as a bean to a server Connector
 * (for the server) or to HttpClient (for the client) will trigger the
 * tracking of the connection statistics for all connections managed
 * by the server Connector or by HttpClient.</p>
 */
@ManagedObject("Tracks statistics on connections")
public class ConnectionStatistics extends AbstractLifeCycle implements Connection.Listener, Dumpable
{
    private final CounterStatistic _connections = new CounterStatistic();
    private final SampleStatistic _connectionsDuration = new SampleStatistic();

    private final LongAdder _bytesIn = new LongAdder();
    private final LongAdder _bytesOut = new LongAdder();
    private final LongAdder _messagesIn = new LongAdder();
    private final LongAdder _messagesOut = new LongAdder();
    private final RateCounter _bytesInRate = new RateCounter();
    private final RateCounter _bytesOutRate = new RateCounter();
    private final RateCounter _messagesInRate = new RateCounter();
    private final RateCounter _messagesOutRate = new RateCounter();

    @ManagedOperation(value = "Resets the statistics", impact = "ACTION")
    public void reset()
    {
        _connections.reset();
        _connectionsDuration.reset();
        _bytesIn.reset();
        _bytesOut.reset();
        _messagesIn.reset();
        _messagesOut.reset();
        _bytesInRate.reset();
        _bytesOutRate.reset();
        _messagesInRate.reset();
        _messagesOutRate.reset();
    }

    @Override
    protected void doStart() throws Exception
    {
        reset();
    }

    @Override
    public void onOpened(Connection connection)
    {
        if (!isStarted())
            return;

        _connections.increment();
    }

    @Override
    public void onClosed(Connection connection)
    {
        if (!isStarted())
            return;

        _connections.decrement();
        _connectionsDuration.record(System.currentTimeMillis() - connection.getCreatedTimeStamp());

        long bytesIn = connection.getBytesIn();
        if (bytesIn > 0)
        {
            _bytesIn.add(bytesIn);
            _bytesInRate.add(bytesIn);
        }

        long bytesOut = connection.getBytesOut();
        if (bytesOut > 0)
        {
            _bytesOut.add(bytesOut);
            _bytesOutRate.add(bytesOut);
        }

        long messagesIn = connection.getMessagesIn();
        if (messagesIn > 0)
        {
            _messagesIn.add(messagesIn);
            _messagesInRate.add(messagesIn);
        }

        long messagesOut = connection.getMessagesOut();
        if (messagesOut > 0)
        {
            _messagesOut.add(messagesOut);
            _messagesOutRate.add(messagesOut);
        }
    }

    @ManagedAttribute("Total number of bytes received by tracked connections")
    public long getReceivedBytes()
    {
        return _bytesIn.sum();
    }

    @ManagedAttribute("Total number of bytes received per second since the last invocation of this method")
    public long getReceivedBytesRate()
    {
        long rate = _bytesInRate.getRate();
        _bytesInRate.reset();
        return rate;
    }

    @ManagedAttribute("Total number of bytes sent by tracked connections")
    public long getSentBytes()
    {
        return _bytesOut.sum();
    }

    @ManagedAttribute("Total number of bytes sent per second since the last invocation of this method")
    public long getSentBytesRate()
    {
        long rate = _bytesOutRate.getRate();
        _bytesOutRate.reset();
        return rate;
    }

    @ManagedAttribute("The max duration of a connection in ms")
    public long getConnectionDurationMax()
    {
        return _connectionsDuration.getMax();
    }

    @ManagedAttribute("The mean duration of a connection in ms")
    public double getConnectionDurationMean()
    {
        return _connectionsDuration.getMean();
    }

    @ManagedAttribute("The standard deviation of the duration of a connection")
    public double getConnectionDurationStdDev()
    {
        return _connectionsDuration.getStdDev();
    }

    @ManagedAttribute("The total number of connections opened")
    public long getConnectionsTotal()
    {
        return _connections.getTotal();
    }

    @ManagedAttribute("The current number of open connections")
    public long getConnections()
    {
        return _connections.getCurrent();
    }

    @ManagedAttribute("The max number of open connections")
    public long getConnectionsMax()
    {
        return _connections.getMax();
    }

    @ManagedAttribute("The total number of messages received")
    public long getReceivedMessages()
    {
        return _messagesIn.sum();
    }

    @ManagedAttribute("Total number of messages received per second since the last invocation of this method")
    public long getReceivedMessagesRate()
    {
        long rate = _messagesInRate.getRate();
        _messagesInRate.reset();
        return rate;
    }

    @ManagedAttribute("The total number of messages sent")
    public long getSentMessages()
    {
        return _messagesOut.sum();
    }

    @ManagedAttribute("Total number of messages sent per second since the last invocation of this method")
    public long getSentMessagesRate()
    {
        long rate = _messagesOutRate.getRate();
        _messagesOutRate.reset();
        return rate;
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            String.format("connections=%s", _connections),
            String.format("durations=%s", _connectionsDuration),
            String.format("bytes in/out=%s/%s", getReceivedBytes(), getSentBytes()),
            String.format("messages in/out=%s/%s", getReceivedMessages(), getSentMessages()));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}

//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Adding an instance of this class as a bean to a ServerConnector
 * or ConnectionFactory (for the server) or to HttpClient (for the client)
 * will trigger the tracking of the connection statistics for all
 * connections managed by the server or by the client.</p>
 * <p>The statistics for a connection are gathered when the connection
 * is closed.</p>
 * <p>ConnectionStatistics instances must be {@link #start() started}
 * to collect statistics, either as part of starting the whole component
 * tree, or explicitly if the component tree has already been started.</p>
 */
@ManagedObject("Tracks statistics on connections")
public class ConnectionStatistics extends AbstractLifeCycle implements Connection.Listener, Dumpable
{
    private final Stats _stats = new Stats("total");
    private final Map<String, Stats> _statsMap = new ConcurrentHashMap<>();

    @ManagedOperation(value = "Resets the statistics", impact = "ACTION")
    public void reset()
    {
        _stats.reset();
        _statsMap.clear();
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
        onTotalOpened(connection);
        onConnectionOpened(connection);
    }

    protected void onTotalOpened(Connection connection)
    {
        _stats.incrementCount();
    }

    protected void onConnectionOpened(Connection connection)
    {
        _statsMap.computeIfAbsent(connection.getClass().getName(), Stats::new).incrementCount();
    }

    @Override
    public void onClosed(Connection connection)
    {
        if (!isStarted())
            return;
        onTotalClosed(connection);
        onConnectionClosed(connection);
    }

    protected void onTotalClosed(Connection connection)
    {
        onClosed(_stats, connection);
    }

    protected void onConnectionClosed(Connection connection)
    {
        Stats stats = _statsMap.get(connection.getClass().getName());
        if (stats != null)
            onClosed(stats, connection);
    }

    private void onClosed(Stats stats, Connection connection)
    {
        stats.decrementCount();
        stats.recordDuration(System.currentTimeMillis() - connection.getCreatedTimeStamp());
        long bytesIn = connection.getBytesIn();
        if (bytesIn > 0)
            stats.recordBytesIn(bytesIn);
        long bytesOut = connection.getBytesOut();
        if (bytesOut > 0)
            stats.recordBytesOut(bytesOut);
        long messagesIn = connection.getMessagesIn();
        if (messagesIn > 0)
            stats.recordMessagesIn(messagesIn);
        long messagesOut = connection.getMessagesOut();
        if (messagesOut > 0)
            stats.recordMessagesOut(messagesOut);
    }

    @ManagedAttribute("Total number of bytes received by tracked connections")
    public long getReceivedBytes()
    {
        return _stats.getReceivedBytes();
    }

    @ManagedAttribute("Total number of bytes received per second since the last invocation of this method")
    public long getReceivedBytesRate()
    {
        return _stats.getReceivedBytesRate();
    }

    @ManagedAttribute("Total number of bytes sent by tracked connections")
    public long getSentBytes()
    {
        return _stats.getSentBytes();
    }

    @ManagedAttribute("Total number of bytes sent per second since the last invocation of this method")
    public long getSentBytesRate()
    {
        return _stats.getSentBytesRate();
    }

    @ManagedAttribute("The max duration of a connection in ms")
    public long getConnectionDurationMax()
    {
        return _stats.getConnectionDurationMax();
    }

    @ManagedAttribute("The mean duration of a connection in ms")
    public double getConnectionDurationMean()
    {
        return _stats.getConnectionDurationMean();
    }

    @ManagedAttribute("The standard deviation of the duration of a connection")
    public double getConnectionDurationStdDev()
    {
        return _stats.getConnectionDurationStdDev();
    }

    @ManagedAttribute("The total number of connections opened")
    public long getConnectionsTotal()
    {
        return _stats.getConnectionsTotal();
    }

    @ManagedAttribute("The current number of open connections")
    public long getConnections()
    {
        return _stats.getConnections();
    }

    @ManagedAttribute("The max number of open connections")
    public long getConnectionsMax()
    {
        return _stats.getConnectionsMax();
    }

    @ManagedAttribute("The total number of messages received")
    public long getReceivedMessages()
    {
        return _stats.getReceivedMessages();
    }

    @ManagedAttribute("Total number of messages received per second since the last invocation of this method")
    public long getReceivedMessagesRate()
    {
        return _stats.getReceivedMessagesRate();
    }

    @ManagedAttribute("The total number of messages sent")
    public long getSentMessages()
    {
        return _stats.getSentMessages();
    }

    @ManagedAttribute("Total number of messages sent per second since the last invocation of this method")
    public long getSentMessagesRate()
    {
        return _stats.getSentMessagesRate();
    }

    public Map<String, Stats> getConnectionStatisticsGroups()
    {
        return _statsMap;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Stats> children = new ArrayList<>();
        children.add(_stats);
        children.addAll(_statsMap.values());
        Dumpable.dumpObjects(out, indent, this, children.toArray());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    public static class Stats implements Dumpable
    {
        private final CounterStatistic _connections = new CounterStatistic();
        private final SampleStatistic _connectionsDuration = new SampleStatistic();
        private final LongAdder _bytesIn = new LongAdder();
        private final RateCounter _bytesInRate = new RateCounter();
        private final LongAdder _bytesOut = new LongAdder();
        private final RateCounter _bytesOutRate = new RateCounter();
        private final LongAdder _messagesIn = new LongAdder();
        private final RateCounter _messagesInRate = new RateCounter();
        private final LongAdder _messagesOut = new LongAdder();
        private final RateCounter _messagesOutRate = new RateCounter();
        private final String _name;

        public Stats(String name)
        {
            _name = name;
        }

        public void reset()
        {
            _connections.reset();
            _connectionsDuration.reset();
            _bytesIn.reset();
            _bytesInRate.reset();
            _bytesOut.reset();
            _bytesOutRate.reset();
            _messagesIn.reset();
            _messagesInRate.reset();
            _messagesOut.reset();
            _messagesOutRate.reset();
        }

        public String getName()
        {
            return _name;
        }

        public long getReceivedBytes()
        {
            return _bytesIn.sum();
        }

        public long getReceivedBytesRate()
        {
            long rate = _bytesInRate.getRate();
            _bytesInRate.reset();
            return rate;
        }

        public long getSentBytes()
        {
            return _bytesOut.sum();
        }

        public long getSentBytesRate()
        {
            long rate = _bytesOutRate.getRate();
            _bytesOutRate.reset();
            return rate;
        }

        public long getConnectionDurationMax()
        {
            return _connectionsDuration.getMax();
        }

        public double getConnectionDurationMean()
        {
            return _connectionsDuration.getMean();
        }

        public double getConnectionDurationStdDev()
        {
            return _connectionsDuration.getStdDev();
        }

        public long getConnectionsTotal()
        {
            return _connections.getTotal();
        }

        public long getConnections()
        {
            return _connections.getCurrent();
        }

        public long getConnectionsMax()
        {
            return _connections.getMax();
        }

        public long getReceivedMessages()
        {
            return _messagesIn.sum();
        }

        public long getReceivedMessagesRate()
        {
            long rate = _messagesInRate.getRate();
            _messagesInRate.reset();
            return rate;
        }

        public long getSentMessages()
        {
            return _messagesOut.sum();
        }

        public long getSentMessagesRate()
        {
            long rate = _messagesOutRate.getRate();
            _messagesOutRate.reset();
            return rate;
        }

        public void incrementCount()
        {
            _connections.increment();
        }

        public void decrementCount()
        {
            _connections.decrement();
        }

        public void recordDuration(long duration)
        {
            _connectionsDuration.record(duration);
        }

        public void recordBytesIn(long bytesIn)
        {
            _bytesIn.add(bytesIn);
            _bytesInRate.add(bytesIn);
        }

        public void recordBytesOut(long bytesOut)
        {
            _bytesOut.add(bytesOut);
            _bytesOutRate.add(bytesOut);
        }

        public void recordMessagesIn(long messagesIn)
        {
            _messagesIn.add(messagesIn);
            _messagesInRate.add(messagesIn);
        }

        public void recordMessagesOut(long messagesOut)
        {
            _messagesOut.add(messagesOut);
            _messagesOutRate.add(messagesOut);
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
            return String.format("%s[%s]", getClass().getSimpleName(), getName());
        }
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

/**
 * A Connector.Listener that gathers Connector and Connections Statistics.
 * Adding an instance of this class as with {@link AbstractConnector#addBean(Object)}
 * will register the listener with all connections accepted by that connector.
 *
 * @deprecated use {@link ServerConnectionStatistics} instead.
 */
@Deprecated
@ManagedObject("Connector Statistics")
public class ConnectorStatistics extends AbstractLifeCycle implements Dumpable, Connection.Listener
{
    private static final Sample ZERO = new Sample();
    private final AtomicLong _startMillis = new AtomicLong(-1L);
    private final CounterStatistic _connectionStats = new CounterStatistic();
    private final SampleStatistic _messagesIn = new SampleStatistic();
    private final SampleStatistic _messagesOut = new SampleStatistic();
    private final SampleStatistic _connectionDurationStats = new SampleStatistic();
    private final ConcurrentMap<Connection, Sample> _samples = new ConcurrentHashMap<>();
    private final LongAdder _closedIn = new LongAdder();
    private final LongAdder _closedOut = new LongAdder();
    private AtomicLong _nanoStamp = new AtomicLong();
    private volatile int _messagesInPerSecond;
    private volatile int _messagesOutPerSecond;

    @Override
    public void onOpened(Connection connection)
    {
        if (isStarted())
        {
            _connectionStats.increment();
            _samples.put(connection, ZERO);
        }
    }

    @Override
    public void onClosed(Connection connection)
    {
        if (isStarted())
        {
            long msgsIn = connection.getMessagesIn();
            long msgsOut = connection.getMessagesOut();
            _messagesIn.record(msgsIn);
            _messagesOut.record(msgsOut);
            _connectionStats.decrement();
            _connectionDurationStats.record(System.currentTimeMillis() - connection.getCreatedTimeStamp());

            Sample sample = _samples.remove(connection);
            if (sample != null)
            {
                _closedIn.add(msgsIn - sample._messagesIn);
                _closedOut.add(msgsOut - sample._messagesOut);
            }
        }
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

    @ManagedAttribute("Connection duration maximum in ms")
    public long getConnectionDurationMax()
    {
        return _connectionDurationStats.getMax();
    }

    @ManagedAttribute("Connection duration mean in ms")
    public double getConnectionDurationMean()
    {
        return _connectionDurationStats.getMean();
    }

    @ManagedAttribute("Connection duration standard deviation")
    public double getConnectionDurationStdDev()
    {
        return _connectionDurationStats.getStdDev();
    }

    @ManagedAttribute("Messages In for all connections")
    public int getMessagesIn()
    {
        return (int)_messagesIn.getTotal();
    }

    @ManagedAttribute("Messages In per connection maximum")
    public int getMessagesInPerConnectionMax()
    {
        return (int)_messagesIn.getMax();
    }

    @ManagedAttribute("Messages In per connection mean")
    public double getMessagesInPerConnectionMean()
    {
        return _messagesIn.getMean();
    }

    @ManagedAttribute("Messages In per connection standard deviation")
    public double getMessagesInPerConnectionStdDev()
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

    @ManagedAttribute("Messages In per connection maximum")
    public int getMessagesOutPerConnectionMax()
    {
        return (int)_messagesIn.getMax();
    }

    @ManagedAttribute("Messages In per connection mean")
    public double getMessagesOutPerConnectionMean()
    {
        return _messagesIn.getMean();
    }

    @ManagedAttribute("Messages In per connection standard deviation")
    public double getMessagesOutPerConnectionStdDev()
    {
        return _messagesIn.getStdDev();
    }

    @ManagedAttribute("Connection statistics started ms since epoch")
    public long getStartedMillis()
    {
        long start = _startMillis.get();
        return start < 0 ? 0 : System.currentTimeMillis() - start;
    }

    @ManagedAttribute("Messages in per second calculated over period since last called")
    public int getMessagesInPerSecond()
    {
        update();
        return _messagesInPerSecond;
    }

    @ManagedAttribute("Messages out per second calculated over period since last called")
    public int getMessagesOutPerSecond()
    {
        update();
        return _messagesOutPerSecond;
    }

    @Override
    public void doStart()
    {
        reset();
    }

    @Override
    public void doStop()
    {
        _samples.clear();
    }

    @ManagedOperation("Reset the statistics")
    public void reset()
    {
        _startMillis.set(System.currentTimeMillis());
        _messagesIn.reset();
        _messagesOut.reset();
        _connectionStats.reset();
        _connectionDurationStats.reset();
        _samples.clear();
    }

    @Override
    @ManagedOperation("dump thread state")
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            "connections=" + _connectionStats,
            "duration=" + _connectionDurationStats,
            "in=" + _messagesIn,
            "out=" + _messagesOut);
    }

    public static void addToAllConnectors(Server server)
    {
        for (Connector connector : server.getConnectors())
        {
            if (connector instanceof Container)
                connector.addBean(new ConnectorStatistics());
        }
    }

    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    private synchronized void update()
    {
        long now = System.nanoTime();
        long then = _nanoStamp.get();
        long duration = now - then;

        if (duration > SECOND_NANOS / 2)
        {
            if (_nanoStamp.compareAndSet(then, now))
            {
                long msgsIn = _closedIn.sumThenReset();
                long msgsOut = _closedOut.sumThenReset();

                for (Map.Entry<Connection, Sample> entry : _samples.entrySet())
                {
                    Connection connection = entry.getKey();
                    Sample sample = entry.getValue();
                    Sample next = new Sample(connection);
                    if (_samples.replace(connection, sample, next))
                    {
                        msgsIn += next._messagesIn - sample._messagesIn;
                        msgsOut += next._messagesOut - sample._messagesOut;
                    }
                }

                _messagesInPerSecond = (int)(msgsIn * SECOND_NANOS / duration);
                _messagesOutPerSecond = (int)(msgsOut * SECOND_NANOS / duration);
            }
        }
    }

    private static class Sample
    {
        Sample()
        {
            _messagesIn = 0;
            _messagesOut = 0;
        }

        Sample(Connection connection)
        {
            _messagesIn = connection.getMessagesIn();
            _messagesOut = connection.getMessagesOut();
        }

        final long _messagesIn;
        final long _messagesOut;
    }
}

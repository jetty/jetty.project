package org.eclipse.jetty.server;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.server.Connector.Statistics;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.util.statistic.SampleStatistic;

class ConnectionStatistics extends AbstractLifeCycle implements Statistics
{
    private final AtomicLong _statsStartedAt = new AtomicLong(-1L);
    private final CounterStatistic _connectionStats = new CounterStatistic();
    private final SampleStatistic _messagesIn = new SampleStatistic();
    private final SampleStatistic _messagesOut = new SampleStatistic();
    private final SampleStatistic _connectionDurationStats = new SampleStatistic();

    
    /* ------------------------------------------------------------ */
    @Override
    public int getBytesIn()
    {
        return -1;
    }
    
    /* ------------------------------------------------------------ */

    @Override
    public int getBytesOut()
    {
        return -1;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Number of connections accepted by the server since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public int getConnections()
    {
        return (int)_connectionStats.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum duration in milliseconds of an open connection since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public long getConnectionsDurationMax()
    {
        return _connectionDurationStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Mean duration in milliseconds of open connections since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public double getConnectionsDurationMean()
    {
        return _connectionDurationStats.getMean();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Standard deviation of duration in milliseconds of open connections since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public double getConnectionsDurationStdDev()
    {
        return _connectionDurationStats.getStdDev();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the connectionsDurationTotal.
     */
    @Override
    public long getConnectionsDurationTotal()
    {
        return _connectionDurationStats.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of requests per connection since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public int getConnectionsMessagesInMax()
    {
        return (int)_messagesIn.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Mean number of requests per connection since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public double getConnectionsMessagesInMean()
    {
        return _messagesIn.getMean();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Standard deviation of number of requests per connection since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public double getConnectionsMessagesInStdDev()
    {
        return _messagesIn.getStdDev();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Number of connections currently open that were opened since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public int getConnectionsOpen()
    {
        return (int)_connectionStats.getCurrent();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of connections opened simultaneously since statsReset() called. Undefined if setStatsOn(false).
     */
    @Override
    public int getConnectionsOpenMax()
    {
        return (int)_connectionStats.getMax();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of requests handled by this connector since last call of statsReset(). If setStatsOn(false) then this is undefined.
     */
    @Override
    public int getMessagesIn()
    {
        return (int)_messagesIn.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of requests handled by this connector since last call of statsReset(). If setStatsOn(false) then this is undefined.
     */
    @Override
    public int getMessagesOut()
    {
        return (int)_messagesIn.getTotal();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if statistics collection is turned on.
     */
    @Override
    public boolean getStatsOn()
    {
        return _statsStartedAt.get() != -1;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Timestamp stats were started at.
     */
    @Override
    public long getStatsOnMs()
    {
        long start = _statsStartedAt.get();

        return (start != -1)?(System.currentTimeMillis() - start):0;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void doStart()
    {
        statsReset();
        _statsStartedAt.set(System.currentTimeMillis());
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void doStop()
    {
        
    }

    /* ------------------------------------------------------------ */
    /**
     * Reset statistics.
     */
    @Override
    public void statsReset()
    {
        _messagesIn.reset();
        _connectionStats.reset();
        _connectionDurationStats.reset();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void connectionOpened()
    {
        if (isStarted())
            _connectionStats.increment();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void connectionUpgraded(long duration, int msgIn, int msgOut)
    {
        _messagesIn.set(msgIn);
        _messagesOut.set(msgOut);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void connectionClosed(long duration, int msgIn, int msgOut)
    {
        _messagesIn.set(msgIn);
        _messagesOut.set(msgOut);
        _connectionStats.decrement();
        _connectionDurationStats.set(duration);
    }
}
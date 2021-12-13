//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.nio.channels.SelectableChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.statistic.RateStatistic;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A Listener that limits the rate at which new connections are accepted</p>
 * <p>
 * If the limits are exceeded, accepting is suspended until the rate is again below
 * the limit, so incoming connections are held in the operating system accept
 * queue (no syn ack sent), where they may either timeout or wait for the server
 * to resume accepting.
 * </p>
 * <p>
 * It can be applied to an entire server or to a specific connector by adding it
 * via {@link Container#addBean(Object)}
 * </p>
 * <p>
 * <b>Usage:</b>
 * </p>
 * <pre>
 *   Server server = new Server();
 *   server.addBean(new AcceptLimit(100,5,TimeUnit.SECONDS,server));
 *   ...
 *   server.start();
 * </pre>
 *
 * @see SelectorManager.AcceptListener
 */
@ManagedObject
public class AcceptRateLimit extends AbstractLifeCycle implements SelectorManager.AcceptListener, Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(AcceptRateLimit.class);

    private final AutoLock _lock = new AutoLock();
    private final Server _server;
    private final List<AbstractConnector> _connectors = new ArrayList<>();
    private final Rate _rate;
    private final int _acceptRateLimit;
    private boolean _limiting;
    private Scheduler.Task _task;

    public AcceptRateLimit(@Name("acceptRateLimit") int acceptRateLimit, @Name("period") long period, @Name("units") TimeUnit units, @Name("server") Server server)
    {
        _server = server;
        _acceptRateLimit = acceptRateLimit;
        _rate = new Rate(period, units);
    }

    public AcceptRateLimit(@Name("limit") int limit, @Name("period") long period, @Name("units") TimeUnit units, @Name("connectors") Connector... connectors)
    {
        this(limit, period, units, (Server)null);
        for (Connector c : connectors)
        {
            if (c instanceof AbstractConnector)
                _connectors.add((AbstractConnector)c);
            else
                LOG.warn("Connector {} is not an AbstractConnector. Connections not limited", c);
        }
    }

    @ManagedAttribute("The accept rate limit")
    public int getAcceptRateLimit()
    {
        return _acceptRateLimit;
    }

    @ManagedAttribute("The accept rate period")
    public long getPeriod()
    {
        return _rate.getPeriod();
    }

    @ManagedAttribute("The accept rate period units")
    public TimeUnit getUnits()
    {
        return _rate.getUnits();
    }

    @ManagedAttribute("The current accept rate")
    public int getRate()
    {
        return _rate.getRate();
    }

    @ManagedAttribute("The maximum accept rate achieved")
    public long getMaxRate()
    {
        return _rate.getMax();
    }

    @ManagedOperation(value = "Resets the accept rate", impact = "ACTION")
    public void reset()
    {
        try (AutoLock l = _lock.lock())
        {
            _rate.reset();
            if (_limiting)
            {
                _limiting = false;
                unlimit();
            }
        }
    }

    protected void age(long period, TimeUnit units)
    {
        _rate.age(period, units);
    }

    @Override
    protected void doStart() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (_server != null)
            {
                for (Connector c : _server.getConnectors())
                {
                    if (c instanceof AbstractConnector)
                        _connectors.add((AbstractConnector)c);
                    else
                        LOG.warn("Connector {} is not an AbstractConnector. Connections not limited", c);
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("AcceptLimit accept<{} rate<{} in {}", _acceptRateLimit, _rate, _connectors);

            for (AbstractConnector c : _connectors)
            {
                c.addBean(this);
            }
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            if (_task != null)
                _task.cancel();
            _task = null;
            for (AbstractConnector c : _connectors)
            {
                c.removeBean(this);
            }
            if (_server != null)
                _connectors.clear();
            _limiting = false;
        }
    }

    protected void limit()
    {
        for (AbstractConnector c : _connectors)
        {
            c.setAccepting(false);
        }
        schedule();
    }

    protected void unlimit()
    {
        for (AbstractConnector c : _connectors)
        {
            c.setAccepting(true);
        }
    }

    @Override
    public void onAccepting(SelectableChannel channel)
    {
        try (AutoLock l = _lock.lock())
        {
            int rate = _rate.record();
            if (LOG.isDebugEnabled())
                LOG.debug("onAccepting rate {}/{} for {} {}", rate, _acceptRateLimit, _rate, channel);
            if (rate > _acceptRateLimit)
            {
                if (!_limiting)
                {
                    _limiting = true;

                    LOG.warn("AcceptLimit rate exceeded {}>{} on {}", rate, _acceptRateLimit, _connectors);
                    limit();
                }
            }
        }
    }

    private void schedule()
    {
        long oldest = _rate.getOldest(TimeUnit.MILLISECONDS);
        long period = TimeUnit.MILLISECONDS.convert(_rate.getPeriod(), _rate.getUnits());
        long delay = period - (oldest > 0 ? oldest : 0);
        if (delay < 0)
            delay = 0;
        if (LOG.isDebugEnabled())
            LOG.debug("schedule {} {}", delay, TimeUnit.MILLISECONDS);
        _task = _connectors.get(0).getScheduler().schedule(this, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run()
    {
        try (AutoLock l = _lock.lock())
        {
            _task = null;
            if (!isRunning())
                return;
            int rate = _rate.getRate();
            if (rate > _acceptRateLimit)
            {
                schedule();
                return;
            }
            if (_limiting)
            {
                _limiting = false;
                LOG.warn("AcceptLimit rate OK {}<={} on {}", rate, _acceptRateLimit, _connectors);
                unlimit();
            }
        }
    }

    private static final class Rate extends RateStatistic
    {
        private Rate(long period, TimeUnit units)
        {
            super(period, units);
        }

        @Override
        protected void age(long period, TimeUnit units)
        {
            super.age(period, units);
        }
    }
}

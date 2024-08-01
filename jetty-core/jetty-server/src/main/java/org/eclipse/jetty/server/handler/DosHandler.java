//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * A Denial of Service Handler.
 * <p>Protect from denial of service attacks by limiting the request rate from remote hosts</p>
 */
@ManagedObject("DOS Prevention Handler")
public class DosHandler extends ConditionalHandler.ElseNext
{
    public static final Function<Request, String> ID_FROM_REMOTE_ADDRESS_PORT = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress.toString();
        return remoteSocketAddress.toString();
    };

    public static final Function<Request, String> ID_FROM_REMOTE_ADDRESS = request ->
    {
        String id;
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress.getAddress().toString();
        return remoteSocketAddress.toString();
    };

    public static final Function<Request, String> ID_FROM_REMOTE_PORT = request ->
    {
        String id;
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return Integer.toString(inetSocketAddress.getPort());
        return remoteSocketAddress.toString();
    };

    public interface RateControl
    {
        boolean isRateExceeded(long now, boolean addSample);

        boolean isIdle(long now);
    }

    public interface RateControlFactory
    {
        RateControl newRate();
    }

    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final Function<Request, String> _getId;
    private final RateControlFactory _rateControlFactory;
    private final Handler _rejector;
    private final int _maxTrackers;
    private CyclicTimeouts<Tracker> _cyclicTimeouts;

    public DosHandler()
    {
        this(null, 100, -1);
    }

    public DosHandler(int maxRequestsPerSecond)
    {
        this(null, maxRequestsPerSecond, -1);
    }

    /**
     * @param getId Function to extract an remote ID from a request.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DosHandler(
        @Name("getId") Function<Request, String> getId,
        @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
        @Name("maxTrackers") int maxTrackers)
    {
        this(null, getId, new ExponentialMovingAverageRateControlFactory(maxRequestsPerSecond), null, maxTrackers);
    }

    /**
     * @param getId Function to extract an remote ID from a request.
     * @param rateControlFactory Factory to create a Rate per Tracker
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DosHandler(
        @Name("getId") Function<Request, String> getId,
        @Name("rateFactory") RateControlFactory rateControlFactory,
        @Name("rejector") Handler rejector,
        @Name("maxTrackers") int maxTrackers)
    {
        this(null, getId, rateControlFactory, rejector, maxTrackers);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}
     * @param getId Function to extract an remote ID from a request.
     * @param rateControlFactory Factory to create a Rate per Tracker
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DosHandler(
        @Name("handler") Handler handler,
        @Name("getId") Function<Request, String> getId,
        @Name("rateFactory") RateControlFactory rateControlFactory,
        @Name("rejector") Handler rejector,
        @Name("maxTrackers") int maxTrackers)
    {
        super(handler);
        installBean(_trackers);
        _getId = Objects.requireNonNullElse(getId, ID_FROM_REMOTE_ADDRESS);
        installBean(_getId);
        _rateControlFactory = Objects.requireNonNull(rateControlFactory);
        installBean(_rateControlFactory);
        _maxTrackers = maxTrackers <= 0 ? 10_000 : maxTrackers;
        _rejector = Objects.requireNonNullElseGet(rejector, DelayedEnhanceYourCalmRejector::new);
        installBean(_rejector);
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        _rejector.setServer(server);
    }

    @Override
    protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        // Reject if we have too many Trackers
        if (_maxTrackers > 0 && _trackers.size() > _maxTrackers)
            return _rejector.handle(request, response, callback);

        // Calculate an id for the request (which may be global empty string)
        String id;
        id = _getId.apply(request);

        // Obtain a tracker
        Tracker tracker = _trackers.computeIfAbsent(id, this::newTracker);

        // If we are not over-limit then handle normally
        if (!tracker.isRateExceeded(request.getBeginNanoTime(), true))
            return nextHandler(request, response, callback);

        // Otherwise reject the request
        return _rejector.handle(request, response, callback);
    }

    Tracker newTracker(String id)
    {
        return new Tracker(id, _rateControlFactory.newRate());
    }

    @Override
    protected void doStart() throws Exception
    {
        _cyclicTimeouts = new CyclicTimeouts<>(getServer().getScheduler())
        {
            @Override
            protected Iterator<Tracker> iterator()
            {
                return _trackers.values().iterator();
            }

            @Override
            protected boolean onExpired(Tracker tracker)
            {
                return tracker.isIdle(System.nanoTime());
            }
        };
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _cyclicTimeouts.destroy();
        _cyclicTimeouts = null;
    }

    /**
     * A RateTracker is associated with a connection, and stores request rate data.
     */
    class Tracker implements CyclicTimeouts.Expirable
    {
        private final AutoLock _lock = new AutoLock();
        private final String _id;
        private final RateControl _rateControl;
        private long _expireAt;

        Tracker(String id, RateControl rateControl)
        {
            _id = id;
            _rateControl = rateControl;
            _expireAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        }

        public String getId()
        {
            return _id;
        }

        RateControl getRateControl()
        {
            return _rateControl;
        }

        public boolean isRateExceeded(long now, boolean addSample)
        {
            try (AutoLock l = _lock.lock())
            {
                CyclicTimeouts<Tracker> cyclicTimeouts = _cyclicTimeouts;
                if (addSample && cyclicTimeouts != null)
                {
                    // schedule a check to remove this tracker if idle
                    _expireAt = now + TimeUnit.SECONDS.toNanos(2L);
                    cyclicTimeouts.schedule(this);
                }
                return _rateControl.isRateExceeded(now, addSample);
            }
        }

        public boolean isIdle(long now)
        {
            try (AutoLock l = _lock.lock())
            {
                return _rateControl.isIdle(now);
            }
        }

        @Override
        public long getExpireNanoTime()
        {
            return _expireAt;
        }

        @Override
        public String toString()
        {
            try (AutoLock l = _lock.lock())
            {
                return "Tracker@%s{rc=%s}".formatted(_id, _rateControl);
            }
        }
    }

    public static class ExponentialMovingAverageRateControlFactory implements RateControlFactory
    {
        private final long _samplePeriod;
        private final double _alpha;
        private final int _maxRate;

        private ExponentialMovingAverageRateControlFactory(int maxRate)
        {
            this(-1, -1.0, maxRate);
        }

        private ExponentialMovingAverageRateControlFactory(long samplePeriodMs, double alpha, int maxRate)
        {
            _samplePeriod = TimeUnit.MILLISECONDS.toNanos(samplePeriodMs <= 0 ? 100 : samplePeriodMs);
            _alpha = alpha <= 0.0 ? 0.2 : alpha;
            if (_samplePeriod > TimeUnit.SECONDS.toNanos(1))
                throw new IllegalArgumentException("Sample period must be less than or equal to 1 second");
            if (_alpha > 1.0)
                throw new IllegalArgumentException("Alpha " + _alpha + " is too large");
            _maxRate = maxRate;
        }

        @Override
        public RateControl newRate()
        {
            return new ExponentialMovingAverageRateControl();
        }

        class ExponentialMovingAverageRateControl implements RateControl
        {
            private double _exponentialMovingAverage;
            private int _sampleCount;
            private long _sampleStart;

            private ExponentialMovingAverageRateControl()
            {
                _sampleStart = System.nanoTime();
            }

            @Override
            public boolean isRateExceeded(long now, boolean addSample)
            {
                // Count the request
                if (addSample)
                    _sampleCount++;

                long elapsedTime = now - _sampleStart;

                // We calculate the rate if:
                //    + we didn't add a sample
                //    + the sample exceeds the rate
                //    + the sample period has been exceeded
                if (!addSample || _sampleCount > _maxRate || (_sampleStart != 0 && elapsedTime > _samplePeriod))
                {
                    double elapsedTime1 = (double)(now - _sampleStart);
                    double count = _sampleCount;
                    if (elapsedTime1 > 0.0)
                    {
                        double currentRate = (count * TimeUnit.SECONDS.toNanos(1L)) / elapsedTime1;
                        // Adjust alpha based on the ratio of elapsed time to the interval to allow for long and short intervals
                        double adjustedAlpha = _alpha * (elapsedTime1 / _samplePeriod);
                        if (adjustedAlpha > 1.0)
                            adjustedAlpha = 1.0; // Ensure adjustedAlpha does not exceed 1.0

                        _exponentialMovingAverage = (adjustedAlpha * currentRate + (1.0 - adjustedAlpha) * _exponentialMovingAverage);
                    }
                    else
                    {
                        // assume count as the rate for the sample.
                        double guessedRate = count * TimeUnit.SECONDS.toNanos(1) / _samplePeriod;
                        _exponentialMovingAverage = (_alpha * guessedRate + (1.0 - _alpha) * _exponentialMovingAverage);
                    }

                    // restart the sample
                    _sampleStart = now;
                    _sampleCount = 0;
                }

                // if the rate has been exceeded?
                return _exponentialMovingAverage > _maxRate;
            }

            @Override
            public boolean isIdle(long now)
            {
                return !isRateExceeded(now, false) && _exponentialMovingAverage <= 0.0001;
            }

            double getCurrentRatePerSecond()
            {
                return _exponentialMovingAverage;
            }
        }
    }

    public static class EnhanceYourCalmRejector extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            Response.writeError(request, response, callback, HttpStatus.ENHANCE_YOUR_CALM_420);
            return true;
        }
    }

    public static class DelayedEnhanceYourCalmRejector extends Handler.Abstract
    {
        record Exchange(Request request, Response response, Callback callback)
        {}

        private final AutoLock _lock = new AutoLock();
        private final Deque<Exchange> _delayQueue = new ArrayDeque<>();
        private final int _maxQueue;
        private final long _delayMs;
        private Scheduler _scheduler;

        public DelayedEnhanceYourCalmRejector()
        {
            this(1000, 1000);
        }

        public DelayedEnhanceYourCalmRejector(long delayMs, int maxQueue)
        {
            _delayMs = delayMs;
            _maxQueue = maxQueue;
        }

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            _scheduler = getServer().getScheduler();
            _scheduler.schedule(this::onTick, _delayMs / 2, TimeUnit.MILLISECONDS);
            addBean(_scheduler);
        }

        @Override
        protected void doStop() throws Exception
        {
            super.doStop();
            removeBean(_scheduler);
            _scheduler = null;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            try (AutoLock ignored = _lock.lock())
            {
                while (_delayQueue.size() >= _maxQueue)
                {
                    Exchange exchange = _delayQueue.removeFirst();
                    Response.writeError(exchange.request, exchange.response, exchange.callback, HttpStatus.ENHANCE_YOUR_CALM_420);
                }
                _delayQueue.addLast(new Exchange(request, response, callback));
            }
            return true;
        }

        private void onTick()
        {
            long expired = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(_delayMs);

            try (AutoLock ignored = _lock.lock())
            {
                Iterator<Exchange> iterator = _delayQueue.iterator();
                while (iterator.hasNext())
                {
                    Exchange exchange = iterator.next();
                    if (exchange.request.getBeginNanoTime() <= expired)
                    {
                        iterator.remove();
                        Response.writeError(exchange.request, exchange.response, exchange.callback, HttpStatus.ENHANCE_YOUR_CALM_420);
                    }
                }
            }

            if (isStarted())
                _scheduler.schedule(this::onTick, _delayMs / 2, TimeUnit.MILLISECONDS);
        }
    }
}

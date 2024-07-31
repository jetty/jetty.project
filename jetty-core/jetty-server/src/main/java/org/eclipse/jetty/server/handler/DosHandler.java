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
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
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

    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final Function<Request, String> _getId;
    private final RateFactory _rateFactory;
    private final int _maxRequestsPerSecond;
    private final int _maxTrackers;
    private final int _maxDelayQueueSize;
    private Scheduler _scheduler;

    public DosHandler()
    {
        this(null, null, 100, -1, null, -1);
    }

    public DosHandler(int maxRequestsPerSecond)
    {
        this(null, null, maxRequestsPerSecond, -1, null, -1);
    }

    /**
     * @param getId Function to extract an remote ID from a request.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     * @param rateFactory Factory to create a Rate per Tracker
     * @param maxDelayQueueSize The maximum number of request to hold in a delay queue before rejecting them.  Delaying rejection can slow some DOS attackers.
     */
    public DosHandler(
        @Name("getId") Function<Request, String> getId,
        @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
        @Name("maxTrackers") int maxTrackers,
        @Name("rateFactory") RateFactory rateFactory,
        @Name("maxDelayQueueSize") int maxDelayQueueSize)
    {
        this(null, getId, maxRequestsPerSecond, maxTrackers, rateFactory, maxDelayQueueSize);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}
     * @param getId Function to extract an remote ID from a request.
     * @param maxRequestsPerSecond The maximum number of requests per second to allow
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     * @param rateFactory Factory to create a Rate per Tracker
     * @param maxDelayQueueSize The maximum number of request to hold in a delay queue before rejecting them.  Delaying rejection can slow some DOS attackers.
     */
    public DosHandler(
        @Name("handler") Handler handler,
        @Name("getId") Function<Request, String> getId,
        @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
        @Name("maxTrackers") int maxTrackers,
        @Name("rateFactory") RateFactory rateFactory,
        @Name("maxDelayQueueSize") int maxDelayQueueSize)
    {
        super(handler);
        installBean(_trackers);
        _getId = Objects.requireNonNullElse(getId, ID_FROM_REMOTE_ADDRESS_PORT);
        _rateFactory = Objects.requireNonNullElseGet(rateFactory, ExponentialMovingAverageRateFactory::new);
        _maxRequestsPerSecond = maxRequestsPerSecond;
        _maxTrackers = maxTrackers <= 0 ? 10_000 : maxTrackers;
        _maxDelayQueueSize = maxDelayQueueSize <= 0 ? 1_000 : maxDelayQueueSize;
    }

    @Override
    protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        // Reject if we have too many Trackers
        if (_maxTrackers > 0 && _trackers.size() > _maxTrackers)
        {
            reject(request, response, callback);
            return true;
        }

        // Calculate an id for the request (which may be global empty string)
        String id;
        id = _getId.apply(request);

        // Obtain a tracker
        Tracker tracker = _trackers.computeIfAbsent(id, this::newTracker);

        // If we are not over-limit then handle normally
        if (!tracker.isRateExceeded(request, response, callback))
            return nextHandler(request, response, callback);

        // Otherwise the Tracker will reject the request
        return true;
    }

    Tracker newTracker(String id)
    {
        return new Tracker(id, _rateFactory.newRate());
    }

    @Override
    protected void doStart() throws Exception
    {
        _scheduler = getServer().getScheduler();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _scheduler = null;
    }

    protected void reject(Request request, Response response, Callback callback)
    {
        Response.writeError(request, response, callback, HttpStatus.ENHANCE_YOUR_CALM_420);
    }

    public interface Rate
    {
        int getRate(long now, boolean addSample);
    }

    public interface RateFactory
    {
        Rate newRate();
    }

    public static class ExponentialMovingAverageRateFactory implements RateFactory
    {
        private final long _samplePeriod;
        private final double _alpha;
        private final int _checkAt;

        private ExponentialMovingAverageRateFactory()
        {
            this(-1, -1.0, 100);
        }

        private ExponentialMovingAverageRateFactory(long samplePeriodMs, double alpha, int checkAt)
        {
            _samplePeriod = TimeUnit.MILLISECONDS.toNanos(samplePeriodMs <= 0 ? 100 : samplePeriodMs);
            _alpha = alpha <= 0.0 ? 0.2 : alpha;
            if (_samplePeriod > TimeUnit.SECONDS.toNanos(1))
                throw new IllegalArgumentException("Sample period must be less than or equal to 1 second");
            if (_alpha > 1.0)
                throw new IllegalArgumentException("Alpha " + _alpha + " is too large");
            _checkAt = checkAt;
        }

        @Override
        public Rate newRate()
        {
            return new ExponentialMovingAverageRate();
        }

        private class ExponentialMovingAverageRate implements Rate
        {
            private double _exponentialMovingAverage;
            private int _sampleCount;
            private long _sampleStart;

            private ExponentialMovingAverageRate()
            {
                _sampleStart = System.nanoTime();
            }

            @Override
            public int getRate(long now, boolean addSample)
            {
                // Count the request
                if (addSample)
                    _sampleCount++;

                long elapsedTime = now - _sampleStart;

                // We calculate the rate if:
                //    + we didn't add a sample
                //    + the sample exceeds the rate
                //    + the sample period has been exceeded
                if (!addSample || _sampleCount > _checkAt || (_sampleStart != 0 && elapsedTime > _samplePeriod))
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
                return (int)_exponentialMovingAverage;
            }
        }
    }

    /**
     * A RateTracker is associated with a connection, and stores request rate data.
     */
    class Tracker extends CyclicTimeout
    {
        protected record Exchange(Request request, Response response, Callback callback)
        {
        }

        private final AutoLock _lock = new AutoLock();
        private final String _id;
        private final Rate _rate;
        private Queue<Exchange> _delayQueue;

        Tracker(String id, Rate rate)
        {
            super(_scheduler);
            _id = id;
            _rate = rate;
        }

        public String getId()
        {
            return _id;
        }

        int getCurrentRatePerSecond(long now)
        {
            try (AutoLock l = _lock.lock())
            {
                return _rate.getRate(now, false);
            }
        }

        public boolean isRateExceeded()
        {
            return isRateExceeded(System.nanoTime(), false);
        }

        public boolean isRateExceeded(long now, boolean addSample)
        {
            try (AutoLock l = _lock.lock())
            {
                return _rate.getRate(now, addSample) > _maxRequestsPerSecond;
            }
        }

        public boolean isRateExceeded(Request request, Response response, Callback callback)
        {
            final long last;

            // Use the request begin time as now. This might not monotonically increase, so algorithm needs
            // to be robust for some jitter.
            long now = request.getBeginNanoTime();
            boolean exceeded;
            try (AutoLock l = _lock.lock())
            {
                exceeded = _rate.getRate(now, true) > _maxRequestsPerSecond;
                if (exceeded)
                {
                    // Add the request to the delay queue
                    if (_delayQueue == null)
                        _delayQueue = new ArrayDeque<>();
                    _delayQueue.add(new Exchange(request, response, callback));

                    // If the delay queue is getting too large, then reject oldest requests
                    while (_delayQueue.size() > _maxDelayQueueSize)
                    {
                        Exchange oldest = _delayQueue.remove();
                        reject(oldest.request, oldest.response, oldest.callback);
                    }
                }
            }

            // Schedule a check on the Tracker to either reject delayed requests, or remove the tracker if idle.
            schedule(2, TimeUnit.SECONDS);

            return exceeded;
        }

        @Override
        public void onTimeoutExpired()
        {
            try (AutoLock l = _lock.lock())
            {
                if (_delayQueue == null || _delayQueue.isEmpty())
                {
                    // Has the Tracker has gone idle, so remove it
                    if (_rate.getRate(System.nanoTime(), false) == 0)
                        _trackers.remove(_id);
                }
                else
                {
                    // Reject the delayed over-limit requests
                    for (Exchange exchange : _delayQueue)
                        reject(exchange.request, exchange.response, exchange.callback);
                    _delayQueue.clear();
                }
            }
        }

        @Override
        public String toString()
        {
            try (AutoLock l = _lock.lock())
            {
                return "Tracker@%s{ema=%d/s}".formatted(_id, _rate);
            }
        }
    }
}

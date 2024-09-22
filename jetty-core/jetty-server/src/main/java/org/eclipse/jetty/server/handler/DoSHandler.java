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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * A Denial of Service Handler.
 * <p>Protect from denial of service attacks by limiting the request rate from remote hosts</p>
 */
@ManagedObject("DoS Prevention Handler")
public class DoSHandler extends ConditionalHandler.ElseNext
{
    /**
     * An id {@link Function} to create an ID from the remote address and port of a {@link Request}
     */
    public static final Function<Request, String> ID_FROM_REMOTE_ADDRESS_PORT = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress.toString();
        return remoteSocketAddress.toString();
    };

    /**
     * An id {@link Function} to create an ID from the remote address of a {@link Request}
     */
    public static final Function<Request, String> ID_FROM_REMOTE_ADDRESS = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress.getAddress().toString();
        return remoteSocketAddress.toString();
    };

    /**
     * An id {@link Function} to create an ID from the remote port of a {@link Request}.
     * This can be useful if there is an untrusted intermediary, where the remote port can be a surrogate for the connection.
     */
    public static final Function<Request, String> ID_FROM_REMOTE_PORT = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return Integer.toString(inetSocketAddress.getPort());
        return remoteSocketAddress.toString();
    };

    /**
     * An id {@link Function} to create an ID from {@link ConnectionMetaData#getId()} of the {@link Request}
     */
    public static final Function<Request, String> ID_FROM_CONNECTION = request -> request.getConnectionMetaData().getId();

    /**
     * An interface implemented to track and control the rate of requests for a specific ID.
     */
    public interface RateControl
    {
        /**
         * Calculate if the rate is exceeded at the given time by adding a sample
         * @param now The {@link NanoTime#now()} at which to calculate the rate
         * @return {@code true} if the rate is currently exceeded
         */
        boolean isRateExceededBySample(long now);

        /**
         * Check if the tracker is now idle
         * @param now The {@link NanoTime#now()} at which to calculate the rate
         * @return {@code true} if the rate is currently near zero
         */
        boolean isIdle(long now);

        /**
         * A factory to create new {@link RateControl} instances
         */
        interface Factory
        {
            RateControl newRateControl();
        }
    }

    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final Function<Request, String> _getId;
    private final RateControl.Factory _rateControlFactory;
    private final Request.Handler _rejectHandler;
    private final int _maxTrackers;
    private CyclicTimeouts<Tracker> _cyclicTimeouts;

    public DoSHandler()
    {
        this(null, 100, -1);
    }

    /**
     * @param maxRequestsPerSecond The maximum requests per second allows per ID.
     */
    public DoSHandler(@Name("maxRequestsPerSecond") int maxRequestsPerSecond)
    {
        this(null, maxRequestsPerSecond, -1);
    }

    /**
     * @param getId Function to extract an remote ID from a request.
     * @param maxRequestsPerSecond The maximum requests per second allows per ID.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("getId") Function<Request, String> getId,
        @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
        @Name("maxTrackers") int maxTrackers)
    {
        this(null, getId, new ExponentialMovingAverageRateControlFactory(maxRequestsPerSecond), null, maxTrackers);
    }

    /**
     * @param getId Function to extract an remote ID from a request.
     * @param rateControlFactory Factory to create a Rate per Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("getId") Function<Request, String> getId,
        @Name("rateFactory") RateControl.Factory rateControlFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers)
    {
        this(null, getId, rateControlFactory, rejectHandler, maxTrackers);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}
     * @param getId Function to extract an remote ID from a request.
     * @param rateControlFactory Factory to create a Rate per Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("handler") Handler handler,
        @Name("getId") Function<Request, String> getId,
        @Name("rateFactory") RateControl.Factory rateControlFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers)
    {
        super(handler);
        installBean(_trackers);
        _getId = Objects.requireNonNullElse(getId, ID_FROM_REMOTE_ADDRESS);
        installBean(_getId);
        _rateControlFactory = Objects.requireNonNull(rateControlFactory);
        installBean(_rateControlFactory);
        _maxTrackers = maxTrackers < 0 ? 10_000 : maxTrackers;
        _rejectHandler = Objects.requireNonNullElseGet(rejectHandler, StatusRejectHandler::new);
        installBean(_rejectHandler);
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        if (_rejectHandler instanceof Handler handler)
            handler.setServer(server);
    }

    @Override
    protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        // Reject if we have too many Trackers
        if (_maxTrackers > 0 && _trackers.size() >= _maxTrackers)
        {
            // Try shrinking the tracker pool
            long now = NanoTime.now();
            _trackers.values().removeIf(tracker -> tracker.isIdle(now));
            if (_trackers.size() >= _maxTrackers)
                return _rejectHandler.handle(request, response, callback);
        }

        // Calculate an id for the request (which may be global empty string)
        String id = _getId.apply(request);

        if (id == null)
            return _rejectHandler.handle(request, response, callback);

        // Obtain a tracker, creating a new one if necessary.  Trackers are removed if CyclicTimeouts#onExpired returns true
        Tracker tracker = _trackers.computeIfAbsent(id, this::newTracker);

        // If we are not over-limit then handle normally
        if (!tracker.isRateExceededBySample(request.getBeginNanoTime()))
            return nextHandler(request, response, callback);

        // Otherwise reject the request
        return _rejectHandler.handle(request, response, callback);
    }

    Tracker newTracker(String id)
    {
        return new Tracker(id, _rateControlFactory.newRateControl());
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
                return tracker.isIdle(NanoTime.now());
            }
        };
        addBean(_cyclicTimeouts);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(_cyclicTimeouts);
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
        private final Duration _idleCheck;
        private long _expireAt;

        Tracker(String id, RateControl rateControl)
        {
            this(id, rateControl, null);
        }

        Tracker(String id, RateControl rateControl, Duration idleCheck)
        {
            _id = id;
            _rateControl = rateControl;
            _idleCheck = idleCheck == null ? Duration.ofSeconds(2) : idleCheck;
            _expireAt = NanoTime.now() + _idleCheck.toNanos();
        }

        public String getId()
        {
            return _id;
        }

        RateControl getRateControl()
        {
            return _rateControl;
        }

        public boolean isRateExceededBySample(long now)
        {
            try (AutoLock l = _lock.lock())
            {
                CyclicTimeouts<Tracker> cyclicTimeouts = _cyclicTimeouts;
                if (cyclicTimeouts != null)
                {
                    // schedule a check to remove this tracker if idle
                    _expireAt = now + _idleCheck.toNanos();
                    cyclicTimeouts.schedule(this);
                }
                return _rateControl.isRateExceededBySample(now);
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

    /**
     * A {@link RateControl.Factory} that uses an
     * <a href="https://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average">Exponential Moving Average</a>
     * to limit the request rate to a maximum number of requests per second.
     */
    public static class ExponentialMovingAverageRateControlFactory implements RateControl.Factory
    {
        private final Duration _samplePeriod;
        private final double _alpha;
        private final int _maxRequestsPerSecond;

        public ExponentialMovingAverageRateControlFactory()
        {
            this(null, -1.0, 1000);
        }

        public ExponentialMovingAverageRateControlFactory(@Name("maxRequestsPerSecond") int maxRateRequestsPerSecond)
        {
            this(null, -1.0, maxRateRequestsPerSecond);
        }

        public ExponentialMovingAverageRateControlFactory(
            @Name("samplePeriodMs") long samplePeriodMs,
            @Name("alpha") double alpha,
            @Name("maxRequestsPerSecond") int maxRequestsPerSecond)
        {
            this(samplePeriodMs <= 0 ? null : Duration.ofMillis(samplePeriodMs), alpha, maxRequestsPerSecond);
        }

        public ExponentialMovingAverageRateControlFactory(
            @Name("samplePeriod") Duration samplePeriod,
            @Name("alpha") double alpha,
            @Name("maxRequestsPerSecond") int maxRequestsPerSecond)
        {
            _samplePeriod = samplePeriod == null ? Duration.ofMillis(100) : samplePeriod;
            _alpha = alpha <= 0.0 ? 0.2 : alpha;
            if (_samplePeriod.compareTo(Duration.ofSeconds(1)) > 0)
                throw new IllegalArgumentException("Sample period must be less than or equal to 1 second");
            if (_alpha > 1.0)
                throw new IllegalArgumentException("Alpha " + _alpha + " is too large");
            _maxRequestsPerSecond = maxRequestsPerSecond;
        }

        @Override
        public RateControl newRateControl()
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
                _sampleStart = NanoTime.now();
            }

            @Override
            public boolean isRateExceededBySample(long now)
            {
                // Count the request
                _sampleCount++;

                long elapsedTime = NanoTime.elapsed(_sampleStart, now);

                // We calculate the moving average if:
                //    + the sample exceeds the rate
                //    + the sample period has been exceeded
                if (_sampleCount > _maxRequestsPerSecond || (_sampleStart != 0 && elapsedTime > _samplePeriod.toNanos()))
                {
                    calculateMovingAverage(now);
                }

                // if the rate has been exceeded?
                return _exponentialMovingAverage > _maxRequestsPerSecond;
            }

            @Override
            public boolean isIdle(long now)
            {
                calculateMovingAverage(now);
                return _exponentialMovingAverage <= 0.0001;
            }

            private void calculateMovingAverage(long now)
            {
                double elapsedTime1 = (double)(now - _sampleStart);
                double count = _sampleCount;
                if (elapsedTime1 > 0.0)
                {
                    double currentRate = (count * TimeUnit.SECONDS.toNanos(1L)) / elapsedTime1;
                    // Adjust alpha based on the ratio of elapsed time to the interval to allow for long and short intervals
                    double adjustedAlpha = _alpha * (elapsedTime1 / _samplePeriod.toNanos());
                    if (adjustedAlpha > 1.0)
                        adjustedAlpha = 1.0; // Ensure adjustedAlpha does not exceed 1.0

                    _exponentialMovingAverage = (adjustedAlpha * currentRate + (1.0 - adjustedAlpha) * _exponentialMovingAverage);
                }
                else
                {
                    // assume count as the rate for the sample.
                    double guessedRate = count * TimeUnit.SECONDS.toNanos(1) / _samplePeriod.toNanos();
                    _exponentialMovingAverage = (_alpha * guessedRate + (1.0 - _alpha) * _exponentialMovingAverage);
                }

                // restart the sample
                _sampleStart = now;
                _sampleCount = 0;
            }

            double getCurrentRatePerSecond()
            {
                return _exponentialMovingAverage;
            }
        }
    }

    /**
     * A Handler to reject DoS requests with a status code or failure.
     */
    public static class StatusRejectHandler implements Request.Handler
    {
        private final int _status;

        public StatusRejectHandler()
        {
            this(-1);
        }

        /**
         * @param status The status used to reject a request, or 0 to fail the request or -1 for a default ({@link HttpStatus#TOO_MANY_REQUESTS_429}.
         */
        public StatusRejectHandler(int status)
        {
            _status = status >= 0 ? status : HttpStatus.TOO_MANY_REQUESTS_429;
            if (!HttpStatus.isClientError(_status) && !HttpStatus.isServerError(_status))
                throw new IllegalArgumentException("status must be a client or server error");
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            if (_status == 0)
                callback.failed(new RejectedExecutionException());
            else
                Response.writeError(request, response, callback, _status);
            return true;
        }
    }

    /**
     * A Handler to reject DoS requests after first delaying them.
     */
    public static class DelayedRejectHandler extends Handler.Abstract
    {
        record Exchange(Request request, Response response, Callback callback)
        {}

        private final AutoLock _lock = new AutoLock();
        private final Deque<Exchange> _delayQueue = new ArrayDeque<>();
        private final int _maxDelayQueue;
        private final long _delayMs;
        private final Request.Handler _reject;
        private Scheduler _scheduler;

        public DelayedRejectHandler()
        {
            this(-1, -1, null);
        }

        /**
         * @param delayMs The delay in milliseconds to hold rejected requests before sending a response or -1 for a default (1000ms)
         * @param maxDelayQueue The maximum number of delayed requests to hold or -1 for a default (1000ms).
         * @param reject The {@link Request.Handler} used to reject {@link Request}s or null for a default ({@link HttpStatus#TOO_MANY_REQUESTS_429}).
         */
        public DelayedRejectHandler(
            @Name("delayMs") long delayMs,
            @Name("maxDelayQueue") int maxDelayQueue,
            @Name("reject") Request.Handler reject)
        {
            _delayMs = delayMs >= 0 ? delayMs : 1000;
            _maxDelayQueue = maxDelayQueue >= 0 ? maxDelayQueue : 1000;
            _reject = Objects.requireNonNullElseGet(reject, () -> new StatusRejectHandler(HttpStatus.TOO_MANY_REQUESTS_429));
        }

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            _scheduler = getServer().getScheduler();
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
            List<Exchange> rejects = null;
            try (AutoLock ignored = _lock.lock())
            {
                while (_delayQueue.size() >= _maxDelayQueue)
                {
                    Exchange exchange = _delayQueue.removeFirst();
                    if (rejects == null)
                        rejects = new ArrayList<>();
                    rejects.add(exchange);
                }

                if (_delayQueue.isEmpty())
                    _scheduler.schedule(this::onTick, _delayMs / 2, TimeUnit.MILLISECONDS);
                _delayQueue.addLast(new Exchange(request, response, callback));
            }

            if (rejects != null)
            {
                for (Exchange exchange : rejects)
                {
                    try
                    {
                        if (!_reject.handle(exchange.request, exchange.response, exchange.callback))
                            exchange.callback.failed(new RejectedExecutionException());
                    }
                    catch (Throwable t)
                    {
                        exchange.callback.failed(t);
                    }
                }
            }

            return true;
        }

        private void onTick()
        {
            long expired = NanoTime.now() - TimeUnit.MILLISECONDS.toNanos(_delayMs);

            List<Exchange> rejects = null;
            try (AutoLock ignored = _lock.lock())
            {
                Iterator<Exchange> iterator = _delayQueue.iterator();
                while (iterator.hasNext())
                {
                    Exchange exchange = iterator.next();
                    if (NanoTime.isBeforeOrSame(exchange.request.getBeginNanoTime(), expired))
                    {
                        iterator.remove();

                        if (rejects == null)
                            rejects = new ArrayList<>();
                        rejects.add(exchange);
                    }
                }

                if (!_delayQueue.isEmpty())
                    _scheduler.schedule(this::onTick, _delayMs / 2, TimeUnit.MILLISECONDS);
            }


            if (rejects != null)
            {
                for (Exchange exchange : rejects)
                {
                    try
                    {
                        Response.writeError(exchange.request, exchange.response, exchange.callback, HttpStatus.TOO_MANY_REQUESTS_429);
                    }
                    catch (Throwable t)
                    {
                        exchange.callback.failed(t);
                    }
                }
            }
        }
    }
}

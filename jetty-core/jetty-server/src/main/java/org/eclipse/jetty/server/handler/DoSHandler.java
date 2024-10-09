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
import org.eclipse.jetty.util.AtomicBiInteger;
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

    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final Function<Request, String> _getId;
    private final Tracker.Factory _trackerFactory;
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
     * @param trackerFactory Factory to create a Tracker
     */
    public DoSHandler(@Name("trackerFactory") Tracker.Factory trackerFactory)
    {
        this(null, trackerFactory, null, -1);
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
        this(null, getId, new LeakyBucketTrackerFactory(maxRequestsPerSecond), null, maxTrackers);
    }

    /**
     * @param getId Function to extract an remote ID from a request.
     * @param trackerFactory Factory to create a Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("getId") Function<Request, String> getId,
        @Name("trackerFactory") Tracker.Factory trackerFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers)
    {
        this(null, getId, trackerFactory, rejectHandler, maxTrackers);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}
     * @param getId Function to extract an remote ID from a request.
     * @param trackerFactory Factory to create a Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value. If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("handler") Handler handler,
        @Name("getId") Function<Request, String> getId,
        @Name("trackerFactory") Tracker.Factory trackerFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers)
    {
        super(handler);
        installBean(_trackers);
        _getId = Objects.requireNonNullElse(getId, ID_FROM_REMOTE_ADDRESS);
        installBean(_getId);
        _trackerFactory = Objects.requireNonNull(trackerFactory);
        installBean(_trackerFactory);
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
            {
                // Try shrinking the tracker pool as if we are at the next sample period already
                long nextIdleCheck = NanoTime.now() + _trackerFactory.getSamplePeriod().getNano();
                _trackers.values().removeIf(tracker -> tracker.isIdle(nextIdleCheck));
                if (_trackers.size() >= _maxTrackers)
                    return _rejectHandler.handle(request, response, callback);
            }
        }

        // Calculate an id for the request (which may be global empty string)
        String id = _getId.apply(request);

        if (id == null)
            return _rejectHandler.handle(request, response, callback);

        // Obtain a tracker, creating a new one if necessary.  Trackers are removed if CyclicTimeouts#onExpired returns true
        Tracker tracker = _trackers.computeIfAbsent(id, this::newTracker);

        // If we are not over-limit then handle normally
        if (tracker.onRequest(request.getBeginNanoTime()))
            return nextHandler(request, response, callback);

        // Otherwise reject the request
        return _rejectHandler.handle(request, response, callback);
    }

    Tracker newTracker(String id)
    {
        Tracker tracker = _trackerFactory.newTracker(id);
        if (_cyclicTimeouts != null)
            _cyclicTimeouts.schedule(tracker);
        return tracker;
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
     * A RateTracker is associated with an id, and stores request rate data.
     */
    public abstract static class Tracker implements CyclicTimeouts.Expirable
    {
        private final String _id;

        protected Tracker(String id)
        {
            _id = id;
        }

        public String getId()
        {
            return _id;
        }

        public abstract boolean isIdle(long now);

        /**
         * Add a request to the tracker and check the rate limit
         *
         * @param now The timestamp of the request
         * @return {@code true} if the request is below the limit
         */
        public abstract boolean onRequest(long now);

        public abstract int getRequestsPerSecond(long now);

        public String toString()
        {
            return "Tracker(%s)@%x".formatted(_id, hashCode());
        }

        public abstract static class Factory
        {
            private static final long MAX_PERIOD_MS = TimeUnit.HOURS.toMillis(1);
            private static final long ENCODE_NANOS_FACTOR = MAX_PERIOD_MS * 10;

            protected final int _maxRequestsPerSecond;
            protected final Duration _samplePeriod;
            protected final int _requestsPerSample;

            public Factory(
                @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
                @Name("samplePeriod") Duration samplePeriod)
            {
                _maxRequestsPerSecond = maxRequestsPerSecond;
                _samplePeriod = samplePeriod == null ? Duration.ofSeconds(1) : samplePeriod;
                if (_samplePeriod.toMillis() >= MAX_PERIOD_MS)
                    throw new IllegalArgumentException("Sample Period must be less that 1 hour");

                double samplesPerSecond = Duration.ofSeconds(1).toNanos() * 1.0D / _samplePeriod.toNanos();
                _requestsPerSample = (int)(1.0D * maxRequestsPerSecond / samplesPerSecond);
            }

            protected static int encodeNanoTime(long nanoTime)
            {
                return (int)(TimeUnit.NANOSECONDS.toMillis(nanoTime) % (ENCODE_NANOS_FACTOR));
            }

            protected static long decodeNanoTime(int encodedTime, long referenceTime)
            {
                return TimeUnit.MILLISECONDS.toNanos((TimeUnit.NANOSECONDS.toMillis(referenceTime) / (ENCODE_NANOS_FACTOR)) * ENCODE_NANOS_FACTOR + encodedTime);
            }

            public abstract Tracker newTracker(String id);

            public abstract Duration getSamplePeriod();
        }
    }

    public static class LeakyBucketTrackerFactory extends Tracker.Factory
    {
        public LeakyBucketTrackerFactory(
            @Name("maxRequestsPerSecond") int maxRequestsPerSecond)
        {
            this(maxRequestsPerSecond, null);
        }

        public LeakyBucketTrackerFactory(
            @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
            @Name("samplePeriod") Duration samplePeriod)
        {
            super(maxRequestsPerSecond, samplePeriod);
        }

        @Override
        public Duration getSamplePeriod()
        {
            return _samplePeriod;
        }

        @Override
        public DoSHandler.Tracker newTracker(String id)
        {
            return new Tracker(id);
        }

        public class Tracker extends DoSHandler.Tracker
        {
            /**
             * The state of the tracker:
             * <ul>
             *     <li>High integer records the timestamp encoded as an int</li>
             *     <li>Low integer records the requests in the current sample.</li>
             * </ul>
             */
            private final AtomicBiInteger _state = new AtomicBiInteger();
            private long _requestsInPreviousSample;

            protected Tracker(String id)
            {
                super(id);
            }

            @Override
            public long getExpireNanoTime()
            {
                return getExpireNanoTime(NanoTime.now());
            }

            public long getExpireNanoTime(long now)
            {
                long state = _state.get();
                int encodedSampleStart = AtomicBiInteger.getHi(state);
                long startSample = decodeNanoTime(encodedSampleStart, now);
                return startSample + _samplePeriod.toNanos();
            }

            @Override
            public boolean onRequest(long now)
            {
                while (true)
                {
                    long state = _state.get();
                    int encodedSampleStart = AtomicBiInteger.getHi(state);
                    if (encodedSampleStart == 0)
                        encodedSampleStart = encodeNanoTime(now);

                    int requests = AtomicBiInteger.getLo(state);

                    // If we have requests to spare?
                    if (requests < _requestsPerSample)
                    {
                        if (_state.compareAndSet(state, encodedSampleStart, requests + 1))
                            return true;
                        continue;
                    }

                    // Are we into a new sample period?
                    long startSample = decodeNanoTime(encodedSampleStart, now);
                    if (NanoTime.elapsed(startSample, now) > _samplePeriod.toNanos())
                    {
                        if (_state.compareAndSet(state, encodeNanoTime(now), 1))
                        {
                            _requestsInPreviousSample = requests;
                            return true;
                        }
                        continue;
                    }

                    if (_state.compareAndSet(state, encodedSampleStart, requests + 1))
                        return false;
                }
            }

            @Override
            public boolean isIdle(long now)
            {
                // We are idle if we roll over to a new sample period, with no requests
                while (true)
                {
                    long state = _state.get();
                    int sampleStartMs = AtomicBiInteger.getHi(state);
                    int requests = AtomicBiInteger.getLo(state);

                    long startSample = decodeNanoTime(sampleStartMs, now);
                    if (NanoTime.elapsed(startSample, now) > _samplePeriod.toNanos())
                    {
                        if (_state.compareAndSet(state, encodeNanoTime(now), 0))
                        {
                            _requestsInPreviousSample = requests;
                            return requests == 0;
                        }
                        continue;
                    }
                    return false;
                }
            }

            @Override
            public int getRequestsPerSecond(long now)
            {
                long state = _state.get();
                int sampleStartMs = AtomicBiInteger.getHi(state);
                int requests = AtomicBiInteger.getLo(state);

                long startSample = decodeNanoTime(sampleStartMs, now);
                long elapsed = NanoTime.elapsed(startSample, now);

                // If we are more than 25% into a sample, then extrapolate from the current request count
                if (elapsed > (_samplePeriod.toNanos() / 4))
                    return Math.toIntExact(requests * TimeUnit.SECONDS.toNanos(1) / elapsed);

                // Otherwise use the previous request count
                return Math.toIntExact(_requestsInPreviousSample * TimeUnit.SECONDS.toNanos(1) / _samplePeriod.toNanos());
            }

            @Override
            public String toString()
            {
                return "%s{%s}".formatted(super.toString(), _state.toString());
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
            if (_status != 0 && _status != HttpStatus.OK_200 && !HttpStatus.isClientError(_status) && !HttpStatus.isServerError(_status))
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
                        if (!_reject.handle(exchange.request, exchange.response, exchange.callback))
                            exchange.callback.failed(new RejectedExecutionException());
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

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A Denial of Service Handler that protects from attacks by limiting the request rate from remote clients.</p>
 */
@ManagedObject("DoS Prevention Handler")
public class DoSHandler extends ConditionalHandler.ElseNext
{
    private static final Logger LOG = LoggerFactory.getLogger(DoSHandler.class);

    /**
     * A {@link Function} to create a remote client identifier from the remote address and remote port of a {@link Request}.
     */
    public static final Function<Request, String> ID_FROM_REMOTE_ADDRESS_PORT = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress.toString();
        return remoteSocketAddress.toString();
    };

    /**
     * A {@link Function} to create a remote client identifier from the remote address of a {@link Request}.
     */
    public static final Function<Request, String> ID_FROM_REMOTE_ADDRESS = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return inetSocketAddress.getAddress().toString();
        return remoteSocketAddress.toString();
    };

    /**
     * A {@link Function} to create a remote client identifier from the remote port of a {@link Request}.
     * Useful if there is an untrusted intermediary, where the remote port can be a surrogate for the connection.
     */
    public static final Function<Request, String> ID_FROM_REMOTE_PORT = request ->
    {
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
            return Integer.toString(inetSocketAddress.getPort());
        return remoteSocketAddress.toString();
    };

    /**
     * A {@link Function} to create a remote client identifier from {@link ConnectionMetaData#getId()} of a {@link Request}.
     */
    public static final Function<Request, String> ID_FROM_CONNECTION = request -> request.getConnectionMetaData().getId();

    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final Function<Request, String> _clientIdFn;
    private final Tracker.Factory _trackerFactory;
    private final Request.Handler _rejectHandler;
    private final int _maxTrackers;
    private final boolean _rejectUntracked;
    private CyclicTimeouts<Tracker> _cyclicTimeouts;

    /**
     * @param trackerFactory Factory to create a Tracker
     */
    public DoSHandler(@Name("trackerFactory") Tracker.Factory trackerFactory)
    {
        this(null, trackerFactory, null, -1);
    }

    /**
     * @param clientIdFn Function to extract a remote client identifier from a request.
     * @param trackerFactory Factory to create a Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value, 0 for unlimited.
     *                    If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("clientIdFn") Function<Request, String> clientIdFn,
        @Name("trackerFactory") Tracker.Factory trackerFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers)
    {
        this(null, clientIdFn, trackerFactory, rejectHandler, maxTrackers);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}
     * @param clientIdFn Function to extract a remote client identifier from a request.
     * @param trackerFactory Factory to create a Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value, 0 for unlimited.
     *                    If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("handler") Handler handler,
        @Name("clientIdFn") Function<Request, String> clientIdFn,
        @Name("trackerFactory") Tracker.Factory trackerFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers)
    {
        this(handler, clientIdFn, trackerFactory, rejectHandler, maxTrackers, false);
    }

    /**
     * @param handler Then next {@link Handler} or {@code null}
     * @param clientIdFn Function to extract a remote client identifier from a request.
     * @param trackerFactory Factory to create a Tracker
     * @param rejectHandler A {@link Handler} used to reject excess requests, or {@code null} for a default.
     * @param maxTrackers The maximum number of remote clients to track or -1 for a default value, 0 for unlimited.
     *                    If this limit is exceeded, then requests from additional remote clients are rejected.
     */
    public DoSHandler(
        @Name("handler") Handler handler,
        @Name("clientIdFn") Function<Request, String> clientIdFn,
        @Name("trackerFactory") Tracker.Factory trackerFactory,
        @Name("rejectHandler") Request.Handler rejectHandler,
        @Name("maxTrackers") int maxTrackers,
        @Name("rejectUntracked") boolean rejectUntracked)
    {
        super(handler);
        installBean(_trackers);
        _clientIdFn = Objects.requireNonNullElse(clientIdFn, ID_FROM_REMOTE_ADDRESS);
        installBean(_clientIdFn);
        _trackerFactory = Objects.requireNonNull(trackerFactory);
        installBean(_trackerFactory);
        // default max trackers to a large, effectively infinite number, but ultimately bounded.
        _maxTrackers = maxTrackers < 0 ? 100_000 : maxTrackers;
        _rejectHandler = Objects.requireNonNullElseGet(rejectHandler, StatusRejectHandler::new);
        installBean(_rejectHandler);
        _rejectUntracked = rejectUntracked;
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

        // Calculate an id for the request (which may be global empty string).
        String id = _clientIdFn.apply(request);

        // Reject or handle untracked request
        if (id == null)
            id = "";

        // Obtain a tracker, creating a new one if necessary (and not too many)
        // Trackers are removed if CyclicTimeouts#onExpired returns true.
        Tracker tracker = _trackers.computeIfAbsent(id, this::newTracker);

        // If we have too many trackers, then we will have a null tracker
        if (tracker == null)
            return _rejectUntracked ? _rejectHandler.handle(request, response, callback) : nextHandler(request, response, callback);

        // IS the request allowed by the tracker?
        boolean allowed = tracker.onRequest(NanoTime.now());
        if (LOG.isDebugEnabled())
            LOG.debug("allowed={} {}", allowed, tracker);
        if (allowed)
            return nextHandler(request, response, callback);

        // Otherwise reject the request as it is over rate
        return _rejectHandler.handle(request, response, callback);
    }

    Tracker newTracker(String id)
    {
        if (_maxTrackers > 0 && _trackers.size() >= _maxTrackers)
           return null;

        Tracker tracker = _trackerFactory.newTracker(id);
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
                return true;
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
    public interface Tracker extends CyclicTimeouts.Expirable
    {
        /**
         * Add a request to the tracker and check the rate limit
         *
         * @param now The timestamp of the request
         * @return {@code true} if the request is below the limit
         */
        boolean onRequest(long now);

        interface Factory
        {
            Tracker newTracker(String id);
        }
    }

    /**
     * The Tracker implements the classic <a href="https://en.wikipedia.org/wiki/Leaky_bucket">Leaky Bucket Algorithm</a>.
     */
    public static class LeakingBucketTrackerFactory implements Tracker.Factory
    {
        private final int _maxRequestsPerSecond;
        private final int _bucketSize;
        private final long _nanosPerDrip;
        private final long _idleTimeout;

        /**
         * @param maxRequestsPerSecond the maximum requests per second allowed by this tracker
         */
        public LeakingBucketTrackerFactory(
            @Name("maxRequestsPerSecond") int maxRequestsPerSecond)
        {
            this(maxRequestsPerSecond, -1, null);
        }

        /**
         * @param maxRequestsPerSecond the maximum requests per second allowed by this tracker
         * @param bucketSize the size of the bucket in request/drips, which is effectively the burst capacity, giving the number
         *        of request that can be handled in excess of the short term rate, before being rejected.
         *        Use -1 for a heuristic value.
         * @param idleTimeout The period to keep an empty bucket before removal, or null to remove a bucket immediately when
         *        empty
         */
        public LeakingBucketTrackerFactory(
            @Name("maxRequestsPerSecond") int maxRequestsPerSecond,
            @Name("bucketSize") int bucketSize,
            @Name("idleTimeout") Duration idleTimeout)
        {
            _maxRequestsPerSecond = maxRequestsPerSecond;
            _nanosPerDrip = TimeUnit.SECONDS.toNanos(1) / _maxRequestsPerSecond;
            _bucketSize = (bucketSize < 0)
                ? _maxRequestsPerSecond
                : bucketSize;
            _idleTimeout = idleTimeout == null ? 0 : idleTimeout.toNanos();
        }

        @Override
        public Tracker newTracker(String id)
        {
            return new LeakingBucketTracker(id);
        }

        private class LeakingBucketTracker implements Tracker
        {
            private final AutoLock _lock = new AutoLock();
            private final String _id;
            private long _lastDripNanoTime;
            private long _expireNanoTime;
            private int _bucket;

            public LeakingBucketTracker(String id)
            {
                _id = id;
                long now = NanoTime.now();
                _lastDripNanoTime = now;
                _expireNanoTime = now + _nanosPerDrip + _idleTimeout;
            }

            @Override
            public long getExpireNanoTime()
            {
                try (AutoLock ignored = _lock.lock())
                {
                    return _expireNanoTime;
                }
            }

            @Override
            public boolean onRequest(long now)
            {
                try (AutoLock ignored = _lock.lock())
                {
                    long elapsedSinceLastDrip = NanoTime.elapsed(_lastDripNanoTime, now);
                    long drips = elapsedSinceLastDrip / _nanosPerDrip;
                    _lastDripNanoTime = _lastDripNanoTime + drips * _nanosPerDrip;
                    _bucket = Math.min(_bucketSize, Math.toIntExact(Math.max(0L, _bucket - drips) + 1));
                    _expireNanoTime = now + _bucket * _nanosPerDrip + _idleTimeout;
                    return _bucket < _bucketSize;
                }
            }

            @Override
            public String toString()
            {
                try (AutoLock ignored = _lock.lock())
                {
                    return "%s@%s{%d/%d}".formatted(getClass().getSimpleName(), _id, _bucket, _maxRequestsPerSecond);
                }
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
        private record Exchange(Request request, Response response, Callback callback)
        {
        }

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

            reject(rejects);

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

            reject(rejects);
        }

        private void reject(List<Exchange> rejects)
        {
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

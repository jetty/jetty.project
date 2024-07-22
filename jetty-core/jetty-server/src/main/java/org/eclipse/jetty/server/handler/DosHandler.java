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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;

public class DosHandler extends ConditionalHandler.ElseNext
{
    private final boolean _useAddress;
    private final boolean _usePort;
    private final Map<String, Tracker> _trackers = new ConcurrentHashMap<>();
    private final int _maxRequestsPerSecond;
    private final int _maxTrackers;
    private Scheduler _scheduler;

    public DosHandler()
    {
        this(null, true, true, 100, -1);
    }

    public DosHandler(Handler handler, boolean useAddress, boolean usePort, int maxRequestsPerSecond, int maxTrackers)
    {
        super(handler);
        installBean(_trackers);
        _useAddress = useAddress;
        _usePort = usePort;
        _maxRequestsPerSecond = maxRequestsPerSecond;
        _maxTrackers = maxTrackers;
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
        SocketAddress remoteSocketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress inetSocketAddress)
        {
            if (_useAddress && _usePort)
                id = inetSocketAddress.toString();
            else if (_useAddress)
                id = inetSocketAddress.getAddress().toString();
            else if (_usePort)
                id = Integer.toString(inetSocketAddress.getPort());
            else
                id = "";
        }
        else
        {
            id = remoteSocketAddress.toString();
        }

        // Obtain a tracker
        Tracker tracker = _trackers.computeIfAbsent(id, Tracker::new);

        // If we are not over-limit then handle normally
        if (!tracker.isRateExceeded(request, response, callback))
            return super.handle(request, response, callback);

        // Otherwise the Tracker will reject the request
        return true;
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

    /**
     * A RateTracker is associated with a connection, and stores request rate
     * data.
     */
    class Tracker extends CyclicTimeout
    {
        protected record Exchange(Request request, Response response, Callback callback)
        {
        }

        private final AutoLock _lock = new AutoLock();
        private final String _id;
        private final long[] _timestamps;
        private Queue<Exchange> _delayQueue;
        private int _next;

        Tracker(String id)
        {
            super(_scheduler);
            _id = id;
            _timestamps = new long[_maxRequestsPerSecond];
            _next = 0;
        }

        public String getId()
        {
            return _id;
        }

        public boolean isRateExceeded(Request request, Response response, Callback callback)
        {
            final long last;
            long now = request.getBeginNanoTime();
            boolean exceeded;
            try (AutoLock l = _lock.lock())
            {
                // record the request time stamp
                last = _timestamps[_next];
                _timestamps[_next] = now;
                _next = (_next + 1) % _timestamps.length;

                // Has the limit been exceeded?
                exceeded = TimeUnit.NANOSECONDS.toSeconds(NanoTime.elapsed(last, now)) < 1L;
                if (exceeded)
                {
                    // Add the request to the delay queue
                    if (_delayQueue == null)
                        _delayQueue = new ArrayDeque<>();
                    _delayQueue.add(new Exchange(request, response, callback));
                    // If the delay queue is getting too large, then reject oldest requests
                    while (_delayQueue.size() > _maxRequestsPerSecond * 2)
                    {
                        Exchange oldest = _delayQueue.remove();
                        reject(oldest.request, oldest.response, oldest.callback);
                    }
                }
            }

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
                    // The Tracker has gone idle, so remove it
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
            return "Tracker/%s".formatted(_id);
        }
    }
}

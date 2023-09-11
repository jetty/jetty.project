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

import java.time.Duration;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A quality of service {@link Handler} that limits the number
 * of concurrent requests, to provide more predictable end-user
 * experience in case descendant {@link Handler}s have limited
 * capacity.</p>
 * <p>This {@code Handler} limits the number of concurrent requests
 * to the number configured via {@link #setMaxRequestCount(int)}.
 * If more requests are received, they are suspended (that is, not
 * forwarded to the child {@code Handler}) and stored in a priority
 * queue.
 * Priorities are determined via {@link #getPriority(Request)},
 * that should return values between {@code 0} (the lowest priority)
 * and positive numbers, typically in the range {@code 0-10}.</p>
 * <p>When a request that is being processed completes, the suspended
 * request that current has the highest priority is resumed.</p>
 * <p>This {@link Handler} is ideal to avoid contending on slow/limited
 * resources such as a JDBC connection pool, avoiding the situation
 * where all server threads blocked contending on the limited
 * resource, therefore leaving threads free to process other
 * requests that do not require access to the limited resource.</p>
 * <p>Requests are resumed in priority order, so that when the
 * server is under load, and there are many requests suspended to
 * be processed, high priority request are processed first.
 * For example, load balancer "ping" requests may have the highest
 * priority, followed by requests performed by admin users, etc.
 * so that regardless of the load, "ping" and "admin" requests will
 * always be able to access the web application.</p>
 */
@ManagedObject
public class QoSHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(QoSHandler.class);
    private static final String EXPIRED_ATTRIBUTE_NAME = QoSHandler.class.getName() + ".expired";

    // hi=permits, lo=suspended.
    private final AtomicBiInteger state = new AtomicBiInteger();
    private final Forwarder forwarder = new Forwarder();
    private final Map<Integer, Queue<Entry>> queues = new ConcurrentHashMap<>();
    private final Set<Integer> priorities = new ConcurrentSkipListSet<>(Comparator.reverseOrder());
    private CyclicTimeouts<Entry> timeouts;
    private int maxRequests;
    private Duration maxSuspend = Duration.ZERO;

    public QoSHandler()
    {
        this(null);
    }

    public QoSHandler(Handler handler)
    {
        super(false, handler);
    }

    /**
     * @return the max number of concurrent requests
     */
    @ManagedAttribute(value = "The maximum number of concurrent requests", readonly = true)
    public int getMaxRequestCount()
    {
        return maxRequests;
    }

    /**
     * <p>Sets the max number of concurrent requests.</p>
     * <p>A negative or zero value indicates to calculate
     * a value based on heuristics, drawn from the number
     * the size of the server thread pool and/or the number
     * of CPU cores.</p>
     *
     * @param maxRequests the max number of concurrent requests
     */
    public void setMaxRequestCount(int maxRequests)
    {
        if (isStarted())
            throw new IllegalStateException("Cannot change maxRequests: " + this);
        this.maxRequests = maxRequests;
    }

    /**
     * @return the max duration of time a request may stay suspended
     */
    public Duration getMaxSuspend()
    {
        return maxSuspend;
    }

    /**
     * <p>Sets the max duration of time a request may stay suspended.</p>
     * <p>Once the duration expires, the request is failed with an HTTP
     * status of {@code 503 Service Unavailable}.</p>
     * <p>{@link Duration#ZERO} means that the request may stay suspended forever.</p>
     *
     * @param maxSuspend the max duration of time a request may stay suspended
     */
    public void setMaxSuspend(Duration maxSuspend)
    {
        if (maxSuspend.isNegative())
            throw new IllegalArgumentException("invalid maxSuspend duration");
        this.maxSuspend = maxSuspend;
    }

    @ManagedAttribute("The number of suspended requests")
    public long getSuspendedRequestCount()
    {
        return state.getLo();
    }

    @Override
    protected void doStart() throws Exception
    {
        timeouts = new Timeouts(getServer().getScheduler());
        addBean(timeouts);

        int maxRequests = getMaxRequestCount();
        if (maxRequests <= 0)
        {
            ThreadPool threadPool = getServer().getThreadPool();
            if (threadPool instanceof ThreadPool.SizedThreadPool sized)
                maxRequests = sized.getMaxThreads() / 2;
            else
                maxRequests = ProcessorUtils.availableProcessors();
            setMaxRequestCount(maxRequests);
        }
        state.set(maxRequests, 0);

        if (LOG.isDebugEnabled())
            LOG.debug("{} initialized maxRequests={}", this, maxRequests);

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(timeouts);
        timeouts.destroy();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} handling {}", this, request);

        while (true)
        {
            long encoded = state.get();
            int permits = AtomicBiInteger.getHi(encoded);
            if (permits > 0)
            {
                if (!state.compareAndSet(encoded, permits - 1, 0))
                    continue;
                return forward(request, response, callback);
            }
            else
            {
                if (request.getAttribute(EXPIRED_ATTRIBUTE_NAME) != null)
                {
                    notAvailable(response, callback);
                }
                else
                {
                    // Avoid this race condition:
                    // T1 in handle() may find no permits, so it will suspend the request.
                    // T2 in resume() finds no suspended requests and increments the permits.
                    // T1 suspends the request, which will remain suspended despite permits are available.
                    // Below, the suspended request count is only incremented if there are no permits.
                    // See correspondent code in resume() and expire().
                    int suspended = AtomicBiInteger.getLo(encoded);
                    if (!state.compareAndSet(encoded, 0, suspended + 1))
                        continue;
                    suspend(request, response, callback);
                }
                return true;
            }
        }
    }

    private static void notAvailable(Response response, Callback callback)
    {
        // Do not suspend again an attempt to handle the expired request/response.
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE_503);
        if (response.isCommitted())
            callback.failed(new IllegalStateException("Response already committed"));
        else
            response.write(true, null, callback);
    }

    /**
     * <p>Returns the priority of the given suspended request,
     * a value greater than or equal to {@code 0}.</p>
     * <p>Priority {@code 0} is the lowest priority.</p>
     * <p>The set of returned priorities should be stable over
     * time, typically constrained in the range {@code 0-10}.</p>
     *
     * @param request the suspended request to compute the priority for
     * @return the priority of the given suspended request, a value {@code >= 0}
     */
    protected int getPriority(Request request)
    {
        return 0;
    }

    /**
     * <p>Fails the given suspended request/response with the given error code and failure.</p>
     * <p>This method is called only for suspended requests, in case of timeout while suspended,
     * or in case of failure when trying to handle a resumed request.</p>
     *
     * @param request the request to fail
     * @param response the response to fail
     * @param callback the callback to complete
     * @param status the failure status code
     * @param failure the failure
     */
    protected void failSuspended(Request request, Response response, Callback callback, int status, Throwable failure)
    {
        Response.writeError(request, response, callback, status, null, failure);
    }

    private boolean forward(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} forwarding {}", this, request);
        request.addHttpStreamWrapper(stream -> new Resumer(stream, request));
        return super.handle(request, response, callback);
    }

    private void suspend(Request request, Response response, Callback callback)
    {
        int priority = Math.max(0, getPriority(request));
        if (LOG.isDebugEnabled())
            LOG.debug("{} suspending priority={} {}", this, priority, request);
        Entry entry = new Entry(request, response, callback, priority);
        queues.compute(priority, (k, v) ->
        {
            if (v != null)
                return v;
            priorities.add(priority);
            return new ConcurrentLinkedQueue<>();
        }).offer(entry);
        timeouts.schedule(entry);
    }

    private void resume()
    {
        // Only increment the permits if there are no suspended requests.
        // In this way, new requests will not steal the permits,
        // so that already suspended requests will not starve.
        while (true)
        {
            if (tryResumeSuspended())
                return;

            // Here there are no suspended requests, so increment the permits.
            // See correspondent code in handle() and expire().
            long encoded = state.get();
            int permits = AtomicBiInteger.getHi(encoded);
            if (state.compareAndSet(encoded, permits + 1, 0))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} no suspended requests to resume", this);
                return;
            }
        }
    }

    private boolean tryResumeSuspended()
    {
        for (Integer priority : priorities)
        {
            Queue<Entry> queue = queues.get(priority);
            Entry entry = queue.poll();
            if (entry != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} resuming {}", this, entry.request);
                // Use an IteratingCallback to avoid StackOverflowError when resuming requests.
                forwarder.offer(entry);
                return true;
            }
        }
        return false;
    }

    private class Entry implements CyclicTimeouts.Expirable
    {
        private final Request request;
        private final Response response;
        private final Callback callback;
        private final int priority;
        private final long expireNanoTime;

        private Entry(Request request, Response response, Callback callback, int priority)
        {
            this.request = request;
            this.response = response;
            this.callback = callback;
            this.priority = priority;
            Duration maxSuspend = getMaxSuspend();
            long suspendNanos = NanoTime.now() + maxSuspend.toNanos();
            if (suspendNanos == Long.MAX_VALUE)
                --suspendNanos;
            this.expireNanoTime = maxSuspend.isZero() ? Long.MAX_VALUE : suspendNanos;
        }

        @Override
        public long getExpireNanoTime()
        {
            return expireNanoTime;
        }

        private void expire()
        {
            // The request timed out, therefore it never acquired a permit.
            boolean removed = queues.get(priority).remove(this);
            if (removed)
            {
                while (true)
                {
                    // See state machine logic in handle() and resume().
                    int suspended = state.getLo();
                    if (!state.compareAndSetLo(suspended, suspended - 1))
                        continue;
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} timeout {}", QoSHandler.this, request);
                    request.setAttribute(EXPIRED_ATTRIBUTE_NAME, true);
                    failSuspended(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, new TimeoutException());
                    return;
                }
            }
        }

        private void resume(Callback callback)
        {
            try
            {
                boolean handled = forward(request, response, callback);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} handled={} {}", QoSHandler.this, handled, request);
                if (!handled)
                    failSuspended(request, response, callback, HttpStatus.NOT_FOUND_404, null);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} failed {}", QoSHandler.this, request, x);
                failSuspended(request, response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500, x);
            }
        }
    }

    private class Forwarder extends IteratingCallback
    {
        private final Queue<Entry> queue = new ConcurrentLinkedQueue<>();
        private Entry entry;

        private void offer(Entry entry)
        {
            queue.offer(entry);
            iterate();
        }

        @Override
        protected Action process()
        {
            entry = queue.poll();
            if (entry == null)
                return Action.IDLE;

            entry.resume(this);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            entry.callback.succeeded();
            super.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            entry.callback.failed(x);
            // Do not call super.failed(), because we want to
            // iterate more, not fail this IteratingCallback.
            super.succeeded();
        }
    }

    private class Resumer extends HttpStream.Wrapper
    {
        private final Request request;

        private Resumer(HttpStream wrapped, Request request)
        {
            super(wrapped);
            this.request = request;
        }

        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} succeeded {}", QoSHandler.this, request);
            resume();
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} failed {}", QoSHandler.this, request, x);
            resume();
        }
    }

    private class Timeouts extends CyclicTimeouts<Entry>
    {
        private Timeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<Entry> iterator()
        {
            // Use Java streams as this is called infrequently.
            return queues.values().stream()
                .flatMap(Queue::stream)
                .iterator();
        }

        @Override
        protected boolean onExpired(Entry entry)
        {
            entry.expire();
            return false;
        }
    }
}

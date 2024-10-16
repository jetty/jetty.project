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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A quality of service {@link Handler} that {@link ConditionalHandler conditionally}
 * limits the number of concurrent requests, to provide more predictable
 * end-user experience in case descendant {@link Handler}s have limited
 * capacity.</p>
 * <p>This {@code Handler} limits the number of concurrent requests
 * to the number configured via {@link #setMaxRequestCount(int)}.
 * If more requests are received, they are suspended (that is, not
 * forwarded to the child {@code Handler}) and stored in a priority
 * queue.</p>
 * <p>The maximum number of suspended request can be set with
 * {@link #setMaxSuspendedRequestCount(int)} to avoid out of memory errors.
 * When this limit is reached, the request will fail fast
 * with status code {@code 503} (not available).</p>
 * <p>Priorities are determined via {@link #getPriority(Request)},
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
public class QoSHandler extends ConditionalHandler.Abstract
{
    private static final Logger LOG = LoggerFactory.getLogger(QoSHandler.class);
    private static final String EXPIRED_ATTRIBUTE_NAME = QoSHandler.class.getName() + ".expired";

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicInteger state = new AtomicInteger();
    private final Map<Integer, Queue<Entry>> queues = new ConcurrentHashMap<>();
    private final Set<Integer> priorities = new ConcurrentSkipListSet<>(Comparator.reverseOrder());
    private CyclicTimeouts<Entry> timeouts;
    private int maxRequests;
    private int maxSuspendedRequests = 1024;
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
     * @return the max number of suspended requests
     */
    @ManagedAttribute(value = "The maximum number of suspended requests", readonly = true)
    public int getMaxSuspendedRequestCount()
    {
        return maxSuspendedRequests;
    }

    /**
     * <p>Sets the max number of suspended requests.</p>
     * <p>Once the max suspended request limit is reached,
     * the request is failed with a HTTP status of
     * {@code 503 Service unavailable}.</p>
     * <p>A negative value indicate an unlimited number
     * of suspended requests.</p>
     *
     * @param maxSuspendedRequests the max number of suspended requests
     */
    public void setMaxSuspendedRequestCount(int maxSuspendedRequests)
    {
        if (isStarted())
            throw new IllegalStateException("Cannot change maxSuspendedRequests: " + this);
        this.maxSuspendedRequests = maxSuspendedRequests;
    }

    /**
     * Get the max duration of time a request may stay suspended.
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
            throw new IllegalArgumentException("Invalid maxSuspend duration");
        this.maxSuspend = maxSuspend;
    }

    @ManagedAttribute("The number of suspended requests")
    public int getSuspendedRequestCount()
    {
        int permits = state.get();
        return Math.max(0, -permits);
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
        state.set(maxRequests);

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
    public boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
    {
        return process(request, response, callback);
    }

    private boolean process(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} processing {}", this, request);

        boolean expired = false;
        boolean tooManyRequests = false;

        // The read lock allows concurrency with resume(),
        // which is the common case, but not with expire().
        lock.readLock().lock();
        try
        {
            int permits = state.decrementAndGet();
            if (permits < 0)
            {
                int maxSuspended = getMaxSuspendedRequestCount();
                if (maxSuspended >= 0 && Math.abs(permits) > maxSuspended)
                {
                    // Reached the limit of suspended requests,
                    // complete the request with 503 unavailable.
                    state.incrementAndGet();
                    tooManyRequests = true;
                }
                else if (request.getAttribute(EXPIRED_ATTRIBUTE_NAME) == null)
                {
                    // Cover this race condition:
                    // T1 in this method may find no permits, so it will suspend the request.
                    // T2 in resume() finds no suspended request yet and increments the permits.
                    // T1 suspends the request, despite permits are available.
                    // This is avoided in resume() using a spin loop to wait for the request to be suspended.
                    // See correspondent state machine logic in resume() and expire().
                    suspend(request, response, callback);
                    return true;
                }
                else
                {
                    // This is a request that was suspended, it expired, and was re-handled.
                    // Do not suspend it again, just complete it with 503 unavailable.
                    state.incrementAndGet();
                    expired = true;
                }
            }
        }
        finally
        {
            lock.readLock().unlock();
        }

        if (expired || tooManyRequests)
        {
            notAvailable(response, callback);
            return true;
        }

        return handleWithPermit(request, response, callback);
    }

    @Override
    protected boolean onConditionsNotMet(Request request, Response response, Callback callback) throws Exception
    {
        return nextHandler(request, response, callback);
    }

    private void notAvailable(Response response, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} rejecting {}", this, response.getRequest());
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

    private boolean handleWithPermit(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} forwarding {}", this, request);
        Request.addCompletionListener(request, this::resume);
        return nextHandler(request, response, callback);
    }

    private void suspend(Request request, Response response, Callback callback)
    {
        int priority = Math.max(0, getPriority(request));
        if (LOG.isDebugEnabled())
            LOG.debug("{} suspending priority={} {}", this, priority, request);
        Entry entry = new Entry(request, response, callback, priority);
        queues.compute(priority, (k, v) ->
        {
            if (v == null)
            {
                priorities.add(priority);
                v = new ConcurrentLinkedQueue<>();
            }
            v.offer(entry);
            return v;
        });
        timeouts.schedule(entry);
    }

    private void resume(Throwable x)
    {
        // Allows concurrency with process(), but not with expire().
        lock.readLock().lock();
        try
        {
            // See correspondent state machine logic in process() and expire().
            int permits = state.incrementAndGet();
            if (permits > 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} no suspended requests to resume", this, x);
                return;
            }

            while (true)
            {
                if (resumeSuspended())
                    return;

                // Found no suspended requests yet, but there will be.
                // This covers the small race window in process(), where
                // the state is updated and then the request suspended.
                Thread.onSpinWait();
            }
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    private boolean resumeSuspended()
    {
        for (Integer priority : priorities)
        {
            Queue<Entry> queue = queues.get(priority);
            if (queue == null)
                return false;
            Entry entry = queue.poll();
            if (entry != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} resuming {}", this, entry.request);
                // Always dispatch to avoid StackOverflowError.
                execute(entry.request, entry);
                return true;
            }
        }
        return false;
    }

    private void execute(Request request, Runnable task)
    {
        request.getComponents().getExecutor().execute(task);
    }

    private class Entry implements CyclicTimeouts.Expirable, Runnable
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
            boolean removed;
            // It should be rare that requests expire.
            // Grab the write lock to atomically operate on the queue and
            // the state, avoiding concurrency with process() and resume().
            lock.writeLock().lock();
            try
            {
                // The request timed out, therefore it was not handled.
                removed = queues.get(priority).remove(this);
                // The remove() may fail to a concurrent resume().
                if (removed)
                {
                    // See correspondent state machine logic in process() and resume().
                    state.incrementAndGet();
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} timeout {}", QoSHandler.this, request);
                    request.setAttribute(EXPIRED_ATTRIBUTE_NAME, true);
                }
            }
            finally
            {
                lock.writeLock().unlock();
            }

            if (removed)
                execute(request, () -> failSuspended(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503, new TimeoutException()));
        }

        @Override
        public void run()
        {
            try
            {
                boolean handled = handleWithPermit(request, response, callback);
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

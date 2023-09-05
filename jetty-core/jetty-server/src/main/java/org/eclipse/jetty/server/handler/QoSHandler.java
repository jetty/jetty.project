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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
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
 * <p>A quality of service {@link Handler} that limits the number
 * of concurrent requests, to provide good end-user experience in
 * case descendant {@link Handler}s have limited capacity.</p>
 * <p>This {@code Handler} limits the number of concurrent requests
 * to the number configured via {@link #setMaxRequests(int)}.
 * If more requests are received, they are suspended (that is, not
 * forwarded to the child {@code Handler}) and stored in a priority
 * queue.
 * Priorities are determined via {@link #getPriority(Request)}, that
 * returns values between {@code 0} (lowest) and the value set via
 * {@link #setMaxPriority(int)} (highest).</p>
 * <p>This filter is ideal to avoid contending on slow/limited
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

    private CyclicTimeouts<Entry> timeouts;
    private List<Queue<Entry>> queues;
    private int maxRequests;
    private Duration maxSuspend = Duration.ZERO;
    private int maxPriority;
    private Semaphore semaphore;

    public QoSHandler()
    {
        super(false);
    }

    /**
     * @return the max number of concurrent requests
     */
    @ManagedAttribute("The maximum number of concurrent requests")
    public int getMaxRequests()
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
    public void setMaxRequests(int maxRequests)
    {
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

    /**
     * @return the max priority of suspended requests
     */
    @ManagedAttribute("The maximum priority of suspended requests")
    public int getMaxPriority()
    {
        return maxPriority;
    }

    /**
     * <p>Sets the max priority that suspended requests may have.</p>
     * <p>The value must be {@code 0} or greater, and typically only
     * few priorities are necessary, say from {@code 0} to {@code 4}
     * for 5 different priorities.</p>
     *
     * @param maxPriority the max priority of suspended requests
     * @see #getPriority(Request)
     */
    public void setMaxPriority(int maxPriority)
    {
        if (maxPriority < 0)
            throw new IllegalArgumentException("invalid maxPriority");
        this.maxPriority = maxPriority;
    }

    @ManagedAttribute("The number of suspended requests")
    public long getSuspendedRequests()
    {
        return queues.stream()
            .mapToLong(Queue::size)
            .sum();
    }

    @Override
    protected void doStart() throws Exception
    {
        timeouts = new Timeouts(getServer().getScheduler());
        addBean(timeouts);

        int maxRequests = getMaxRequests();
        if (maxRequests <= 0)
        {
            ThreadPool threadPool = getServer().getThreadPool();
            if (threadPool instanceof ThreadPool.SizedThreadPool sized)
                maxRequests = sized.getMaxThreads() / 2;
            else
                maxRequests = ProcessorUtils.availableProcessors();
            setMaxRequests(maxRequests);
        }
        semaphore = new Semaphore(maxRequests);

        int maxPriority = getMaxPriority();
        int capacity = maxPriority + 1;
        queues = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; ++i)
        {
            queues.add(new ConcurrentLinkedQueue<>());
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} initialized maxRequests={} maxPriority={}", this, maxRequests, maxPriority);

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
        return process(request, response, callback);
    }

    private boolean process(Request request, Response response, Callback callback) throws Exception
    {
        if (semaphore.tryAcquire())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} forwarding {}", this, request);
            request.addHttpStreamWrapper(stream -> new Resumer(stream, request));
            return super.handle(request, response, callback);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} suspending {}", this, request);
            int priority = getPriority(request);
            priority = Math.min(Math.max(0, priority), getMaxPriority());
            Entry entry = new Entry(request, response, callback, priority);
            queues.get(priority).offer(entry);
            timeouts.schedule(entry);
            return true;
        }
    }

    /**
     * <p>Returns the priority of the given suspended request.</p>
     *
     * @param request the suspended request to compute the priority for
     * @return the priority of the given suspended request
     * @see #setMaxPriority(int)
     */
    protected int getPriority(Request request)
    {
        return 0;
    }

    private void processAndComplete(Request request, Response response, Callback callback)
    {
        try
        {
            boolean handled = process(request, response, callback);
            if (LOG.isDebugEnabled())
                LOG.debug("{} handled={} {}", this, handled, request);
            if (!handled)
                Response.writeError(request, response, callback, HttpStatus.NOT_FOUND_404);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} failed {}", this, request, x);
            Response.writeError(request, response, callback, x);
        }
    }

    private void resume()
    {
        semaphore.release();
        ListIterator<Queue<Entry>> iterator = queues.listIterator(queues.size());
        while (iterator.hasPrevious())
        {
            Queue<Entry> queue = iterator.previous();
            Entry entry = queue.poll();
            if (entry != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} resuming {}", this, entry.request);
                // Try again to acquire a semaphore pass.
                // If it cannot be acquired, the request may
                // be re-prioritized with a higher priority.
                processAndComplete(entry.request, entry.response, entry.callback);
                return;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} no suspended requests to resume", this);
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
            this.expireNanoTime = maxSuspend.isZero() ? Long.MAX_VALUE : NanoTime.now() + maxSuspend.toNanos();
        }

        @Override
        public long getExpireNanoTime()
        {
            return expireNanoTime;
        }

        private void expire()
        {
            // The request timed out, therefore it never acquired a semaphore pass.
            boolean removed = queues.get(priority).remove(this);
            if (removed)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} timeout {}", QoSHandler.this, request);
                Response.writeError(request, response, callback, HttpStatus.SERVICE_UNAVAILABLE_503);
            }
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
            return queues.stream()
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

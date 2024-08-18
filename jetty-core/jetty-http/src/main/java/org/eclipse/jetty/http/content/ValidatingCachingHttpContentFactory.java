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

package org.eclipse.jetty.http.content;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>
 * {@link HttpContent.Factory} implementation of {@link CachingHttpContentFactory} which evicts invalid entries from the cache.
 * Uses a validationTime parameter to check files when they are accessed and/or sweepDelay parameter for a periodic sweep
 * for invalid entries in the cache.
 * </p>
 * <p>
 * {@link HttpContent} validation checks are configured through the {@code validationTime} parameter in the constructor.
 * If an {@link HttpContent} is found to be invalid it will be removed from the cache.
 * A value of -1 means that the cached {@link HttpContent} will never be checked if it is still valid,
 * a value of 0 means it is checked on every request,
 * a positive value indicates the number of milliseconds of the minimum time between validation checks.
 * </p>
 * <p>
 * This also remember a missed entry for the time set by {@code validationTime}ms. After this has
 * elapsed the entry will be invalid and will be evicted from the cache at the next access.
 * </p>
 */
public class ValidatingCachingHttpContentFactory extends CachingHttpContentFactory implements Runnable
{
    private final Scheduler _scheduler;
    private final long _sweepDelay;
    private final long _validationTime;
    private final long _maxCacheIdleTime;

    /**
     * Construct a {@link ValidatingCachingHttpContentFactory} which validates entries upon use to check if they
     * are still valid.
     *
     * @param authority the wrapped {@link HttpContent.Factory} to use.
     * @param validationPeriod time between filesystem checks in ms to see if an {@link HttpContent} is still valid (-1 never validate, 0 always validate).
     * @param bufferPool the {@link org.eclipse.jetty.io.ByteBufferPool} to use.
     */
    public ValidatingCachingHttpContentFactory(@Name("authority") HttpContent.Factory authority,
                                               @Name("validationPeriod") long validationPeriod,
                                               @Name("bufferPool") ByteBufferPool.Sized bufferPool)
    {
        this(authority, validationPeriod, bufferPool, null, -1, -1);
    }

    /**
     * Construct a {@link ValidatingCachingHttpContentFactory} which validates entries upon use to check if they
     * are still valid and an optional period sweeper of the cache to find invalid and old entries to evict.
     *
     * @param authority the wrapped {@link HttpContent.Factory} to use.
     * @param validationPeriod time between filesystem checks in ms to see if an {@link HttpContent} is still valid (-1 never validate, 0 always validate).
     * @param bufferPool the {@link org.eclipse.jetty.io.ByteBufferPool} to use.
     * @param scheduler scheduler to use for the sweeper, can be null to not use sweeper.
     * @param sweepPeriod time between runs of the sweeper in ms (if 0 never sweep for invalid entries).
     * @param idleTimeout amount of time in ms an entry can be unused before evicted by the sweeper (if 0 never evict unused entries).
     */
    public ValidatingCachingHttpContentFactory(@Name("authority") HttpContent.Factory authority,
                                               @Name("validationPeriod") long validationPeriod,
                                               @Name("byteBufferPool") ByteBufferPool.Sized bufferPool,
                                               @Name("scheduler") Scheduler scheduler,
                                               @Name("sweepPeriod") long sweepPeriod,
                                               @Name("idleTimeout") long idleTimeout)
    {
        super(authority, bufferPool);
        _validationTime = validationPeriod;
        _scheduler = scheduler;
        _sweepDelay = sweepPeriod;
        _maxCacheIdleTime = idleTimeout;
        if (scheduler != null && sweepPeriod > 0)
            schedule();
    }

    @Override
    protected boolean isCacheable(HttpContent httpContent)
    {
        if (httpContent == null)
            return (_validationTime != 0);
        return super.isCacheable(httpContent);
    }

    private void schedule()
    {
        _scheduler.schedule(this, _sweepDelay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run()
    {
        try
        {
            ConcurrentMap<String, CachingHttpContent> cache = getCache();
            for (Map.Entry<String, CachingHttpContent> entry : cache.entrySet())
            {
                CachingHttpContent value = entry.getValue();
                if (_maxCacheIdleTime > 0 && NanoTime.since(value.getLastAccessedNanos()) > TimeUnit.MILLISECONDS.toNanos(_maxCacheIdleTime))
                    removeFromCache(value);
                else if (!value.isValid())
                    removeFromCache(value);
            }
        }
        finally
        {
            schedule();
        }
    }

    @Override
    protected CachingHttpContent newCachedContent(String p, HttpContent httpContent)
    {
        return new ValidatingCachedContent(p, httpContent, _validationTime);
    }

    @Override
    protected CachingHttpContent newNotFoundContent(String p)
    {
        return new ValidatingNotFoundContent(p, _validationTime);
    }

    protected class ValidatingCachedContent extends CachedHttpContent
    {
        private final long _validationTime;
        private final AtomicLong _lastValidated = new AtomicLong();

        public ValidatingCachedContent(String key, HttpContent httpContent, long validationTime)
        {
            super(key, httpContent);
            _lastValidated.set(NanoTime.now());
            _validationTime = validationTime;
        }

        @Override
        public boolean isValid()
        {
            if (_validationTime < 0)
            {
                return true;
            }
            else if (_validationTime > 0)
            {
                long now = NanoTime.now();
                if (_lastValidated.updateAndGet(lastChecked ->
                    (NanoTime.elapsed(lastChecked, now) > TimeUnit.MILLISECONDS.toNanos(_validationTime)) ? now : lastChecked) != now)
                    return true;
            }

            return Objects.equals(getLastModifiedInstant(), getWrapped().getLastModifiedInstant());
        }
    }

    protected static class ValidatingNotFoundContent extends NotFoundHttpContent
    {
        private final long _validationTime;
        private final AtomicLong _lastValidated = new AtomicLong();

        public ValidatingNotFoundContent(String key, long validationTime)
        {
            super(key);
            _validationTime = validationTime;
            _lastValidated.set(NanoTime.now());
        }

        @Override
        public boolean isValid()
        {
            if (_validationTime < 0)
                return true;
            if (_validationTime > 0)
                return NanoTime.since(_lastValidated.get()) < TimeUnit.MILLISECONDS.toNanos(_validationTime);
            return false;
        }
    }
}

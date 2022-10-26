//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
public class EvictingCachingContentFactory extends CachingHttpContentFactory implements Runnable
{
    private final Scheduler _scheduler;
    private final long _sweepDelay;
    private final long _validationTime;
    private final long _maxCacheIdleTime;

    public EvictingCachingContentFactory(@Name("authority") HttpContent.Factory authority,
                                         @Name("validationTime") long validationTime)
    {
        this(authority, validationTime, null, -1, -1);
    }

    public EvictingCachingContentFactory(@Name("authority") HttpContent.Factory authority,
                                         @Name("validationTime") long validationTime,
                                         @Name("scheduler") Scheduler scheduler,
                                         @Name("sweepDelay") long sweepDelay,
                                         @Name("maxCacheIdleTime") long maxCacheIdleTime)
    {
        super(authority);
        _validationTime = validationTime;
        _scheduler = scheduler;
        _sweepDelay = sweepDelay;
        _maxCacheIdleTime = maxCacheIdleTime;
        if (scheduler != null && sweepDelay > 0)
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
        ConcurrentMap<String, CachingHttpContent> cache = getCache();
        for (Map.Entry<String, CachingHttpContent> entry : cache.entrySet())
        {
            CachingHttpContent value = entry.getValue();
            if (_maxCacheIdleTime > 0 && NanoTime.since(value.getLastAccessedNanos()) > TimeUnit.MILLISECONDS.toNanos(_maxCacheIdleTime))
                removeFromCache(value);
            else if (!value.isValid())
                removeFromCache(value);
        }
        schedule();
    }

    @Override
    protected CachingHttpContent newCachedContent(String p, HttpContent httpContent)
    {
        return new EvictingCachedContent(p, httpContent, _validationTime);
    }

    @Override
    protected CachingHttpContent newNotFoundContent(String p)
    {
        return new EvictingNotFoundContent(p, _validationTime);
    }

    protected class EvictingCachedContent extends CachedHttpContent
    {
        private final long _validationTime;
        private final AtomicLong _lastValidated = new AtomicLong();

        public EvictingCachedContent(String key, HttpContent httpContent, long validationTime)
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

    protected static class EvictingNotFoundContent extends NotFoundHttpContent
    {
        private final long _validationTime;
        private final AtomicLong _lastValidated = new AtomicLong();

        public EvictingNotFoundContent(String key, long validationTime)
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

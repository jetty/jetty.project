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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.Scheduler;

public class EvictingCachingContentFactory extends CachingHttpContentFactory implements Runnable
{
    private final Scheduler _scheduler;
    private final long _delay;

    public EvictingCachingContentFactory(HttpContent.Factory authority, Scheduler scheduler, long delay)
    {
        super(authority);
        _scheduler = scheduler;
        _delay = delay;
        setEvictionTime(0);
    }

    private void schedule()
    {
        _scheduler.schedule(this, _delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run()
    {
        ConcurrentMap<String, CachingHttpContent> cache = getCache();
        for (Map.Entry<String, CachingHttpContent> entry : cache.entrySet())
        {
            CachingHttpContent value = entry.getValue();
            if (value instanceof EvictingCachedContent content)
            {
                if (!content.checkValid())
                    removeFromCache(content);
            }
            else if (value instanceof EvictingNotFoundContent content)
            {
                if (!content.checkValid())
                    removeFromCache(content);
            }
            else
            {
                if (!value.isValid())
                    removeFromCache(value);
            }
        }
        schedule();
    }

    @Override
    protected CachingHttpContent newCachedContent(String p, HttpContent httpContent, long evictionTime)
    {
        return new EvictingCachedContent(p, httpContent, evictionTime);
    }

    @Override
    protected CachingHttpContent newNotFoundContent(String p, long evictionTime)
    {
        return new NotFoundContent(p, evictionTime);
    }

    protected static class EvictingCachedContent extends CachedContent
    {
        public EvictingCachedContent(String key, HttpContent httpContent, long evictionTime)
        {
            super(key, httpContent, evictionTime);
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        public boolean checkValid()
        {
            return super.isValid();
        }
    }

    protected static class EvictingNotFoundContent extends NotFoundContent
    {
        public EvictingNotFoundContent(String key, long evictionTime)
        {
            super(key, evictionTime);
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        public boolean checkValid()
        {
            return super.isValid();
        }
    }
}

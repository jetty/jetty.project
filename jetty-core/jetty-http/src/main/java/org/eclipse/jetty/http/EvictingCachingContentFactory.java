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

public class EvictingCachingContentFactory extends CachingContentFactory implements Runnable
{
    private final Scheduler _scheduler;
    private final long _delay;
    private final TimeUnit _timeUnit;

    public EvictingCachingContentFactory(HttpContent.Factory authority, Scheduler scheduler)
    {
        this(authority, scheduler, 1, TimeUnit.MINUTES);
    }

    public EvictingCachingContentFactory(HttpContent.Factory authority, Scheduler scheduler, long delay, TimeUnit timeUnit)
    {
        super(authority);
        _scheduler = scheduler;
        _delay = delay;
        _timeUnit = timeUnit;
    }

    private void schedule()
    {
        _scheduler.schedule(this, _delay, _timeUnit);
    }

    @Override
    protected boolean isValid(CachingHttpContent content)
    {
        return false;
    }

    @Override
    public void run()
    {
        ConcurrentMap<String, CachingHttpContent> cache = getCache();
        for (Map.Entry<String, CachingHttpContent> entry : cache.entrySet())
        {
            if (!super.isValid(entry.getValue()))
                removeFromCache(entry.getValue());
        }
        schedule();
    }
}

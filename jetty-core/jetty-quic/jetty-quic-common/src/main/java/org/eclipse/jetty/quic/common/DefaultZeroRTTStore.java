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

package org.eclipse.jetty.quic.common;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultZeroRTTStore implements ZeroRTTStore
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultZeroRTTStore.class);

    private final AutoLock lock = new AutoLock();
    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void store(Entry entry)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("storing {} on {}", entry, this);
        try (var _ = lock.lock())
        {
            entries.add(entry);
        }
    }

    @Override
    public Entry match(Predicate<Entry> filter)
    {
        Entry result = null;
        try (var _ = lock.lock())
        {
            Iterator<Entry> iterator = entries.iterator();
            while (iterator.hasNext())
            {
                Entry entry = iterator.next();
                if (entry.expired())
                    continue;
                if (filter.test(entry))
                {
                    iterator.remove();
                    result = entry;
                    break;
                }
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("matched {} on {}", result, this);
        return result;
    }

    @Override
    public int size()
    {
        try (var _ = lock.lock())
        {
            return entries.size();
        }
    }

    @Override
    public String toString()
    {
        return "%s@%x[size=%d]".formatted(TypeUtil.toShortName(getClass()), hashCode(), size());
    }
}

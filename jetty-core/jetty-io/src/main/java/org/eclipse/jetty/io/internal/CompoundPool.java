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

package org.eclipse.jetty.io.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

import org.eclipse.jetty.util.Pool;

/**
 * <p>A {@link Pool} implementation that uses a primary pool which overflows to a secondary pool.</p>
 *
 * @param <P> the type of the pooled objects
 */
public class CompoundPool<P> implements Pool<P>
{
    private final Pool<P> primaryPool;
    private final Pool<P> secondaryPool;

    public CompoundPool(Pool<P> primaryPool, Pool<P> secondaryPool)
    {
        this.primaryPool = primaryPool;
        this.secondaryPool = secondaryPool;
    }

    @Override
    public Entry<P> reserve()
    {
        Entry<P> primary = primaryPool.reserve();
        return primary != null ? primary : secondaryPool.reserve();
    }

    @Override
    public Entry<P> acquire()
    {
        Entry<P> primary = primaryPool.acquire();
        return primary != null ? primary : secondaryPool.acquire();
    }

    @Override
    public boolean isTerminated()
    {
        return primaryPool.isTerminated();
    }

    @Override
    public Collection<Entry<P>> terminate()
    {
        Collection<Entry<P>> entries = new ArrayList<>();
        entries.addAll(primaryPool.terminate());
        entries.addAll(secondaryPool.terminate());
        return entries;
    }

    @Override
    public int size()
    {
        return primaryPool.size() + secondaryPool.size();
    }

    @Override
    public int getMaxSize()
    {
        return primaryPool.getMaxSize() + secondaryPool.getMaxSize();
    }

    @Override
    public Stream<Entry<P>> stream()
    {
        return Stream.concat(primaryPool.stream(), secondaryPool.stream());
    }
}

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

package org.eclipse.jetty.server;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * Common components made available via a {@link Request}.
 */
public interface Components
{
    /**
     * @return the {@link ByteBufferPool} associated with the {@link Request}
     */
    ByteBufferPool getByteBufferPool();

    /**
     * @return the {@link Scheduler} associated with the {@link Request}
     */
    Scheduler getScheduler();

    /**
     * @return the {@link ThreadPool} associated with the {@link Request}
     * @deprecated use {@link #getExecutor()} instead
     */
    @Deprecated(since = "12.0.13", forRemoval = true)
    ThreadPool getThreadPool();

    /**
     * @return the {@link Executor} associated with the {@link Request}
     */
    default Executor getExecutor()
    {
        return getThreadPool();
    }

    /**
     * <p>A map-like object that can be used as a cache (for example, as a cookie cache).</p>
     * <p>The cache will have a life cycle limited by the connection, i.e. no cache map will live
     * longer that the connection associated with it.  However, a cache may have a shorter life
     * than a connection (e.g. it may be discarded for implementation reasons).  A cache map is
     * guaranteed to be given to only a single request concurrently (scoped by
     * {@link org.eclipse.jetty.server.internal.HttpChannelState}), so objects saved there do not
     * need to be made safe from access by simultaneous request.
     * If the connection is known to be non-persistent then the cache may be a noop
     * cache and discard all items set on it.</p>
     *
     * @return A map-like object, which may be an empty implementation that discards all items.
     */
    Attributes getCache();
}

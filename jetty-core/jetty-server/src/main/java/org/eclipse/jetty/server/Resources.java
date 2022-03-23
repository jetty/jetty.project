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

package org.eclipse.jetty.server;

import java.util.Map;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;

/**
 * Resources made available via a {@link Request}
 * TODO flesh out this idea... maybe better name?
 */
public interface Resources
{
    ByteBufferPool getByteBufferPool();

    Scheduler getScheduler();

    ThreadPool getThreadPool();

    /**
     * A Map which can be used as a cache for object (e.g. Cookie cache).
     * The cache will have a life cycle limited by the connection, i.e. no cache map will live
     * longer that the connection associated with it.  However, a cache may have a shorter life
     * than a connection (e.g. it may be discarded for implementation reasons).  A cache map is
     * guaranteed to be give to only a single request concurrently, so objects saved there do not
     * need to be made safe from access by simultaneous request.
     * If the connection is known to be none-persistent then the cache may be a noop cache and discard
     * all items set on it.
     * @return A Map, which may be an empty map that discards all items.
     */
    Map<String, Object> getCache();
}

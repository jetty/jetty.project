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

package org.eclipse.jetty.client;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.jetty.http.HttpStatus;

/**
 * <p>A cache for HTTP redirects.</p>
 * <p>For a request that is redirected, implementations may cache
 * the redirected HTTP method and URI, so that following requests
 * for the same original HTTP method and URI are performed using
 * the redirected HTTP method and URI.</p>
 */
public interface RedirectCache
{
    /**
     * <p>Returns a cached redirect for the given method and URI.</p>
     *
     * @param original the original method and URI
     * @return the redirect method and URI, or {@code null} if not found
     */
    MethodOriginTarget get(MethodOriginTarget original);

    /**
     * <p>Caches a redirect.</p>
     *
     * @param original the original method and URI
     * @param redirect the redirect method and URI
     */
    void put(MethodOriginTarget original, int status, MethodOriginTarget redirect);

    /**
     * @return the number of cached redirects
     */
    int size();

    /**
     * <p>Clears all cached redirects.</p>
     */
    void clear();

    /**
     * <p>A cached redirect entry used for both source and target.</p>
     *
     * @param method the original or redirect HTTP method
     * @param origin the original or redirect origin (scheme, host, port)
     * @param target the original or redirect target (path, authority, asterisk, uri)
     */
    record MethodOriginTarget(String method, URI origin, String target)
    {
    }

    /**
     * <p>Default implementation of {@link RedirectCache}.</p>
     * <p>This implementation only caches permanent redirects
     * corresponding to HTTP statuses {@code 301} and {@code 308}.</p>
     * <p>This implementation uses a configurable size-bounded LRU cache.</p>
     */
    class Default implements RedirectCache
    {
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Map<MethodOriginTarget, MethodOriginTarget> cache;

        /**
         * Creates a cache with the specified maximum size.
         *
         * @param maxSize the maximum number of entries (must be positive)
         */
        public Default(int maxSize)
        {
            if (maxSize <= 0)
                throw new IllegalArgumentException("maxSize must be positive");
            this.cache = new LinkedHashMap<>(16, 0.75f, true)
            {
                @Override
                protected boolean removeEldestEntry(Map.Entry<MethodOriginTarget, MethodOriginTarget> eldest)
                {
                    return size() > maxSize;
                }
            };
        }

        @Override
        public MethodOriginTarget get(MethodOriginTarget original)
        {
            lock.readLock().lock();
            try
            {
                return cache.get(original);
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public void put(MethodOriginTarget original, int status, MethodOriginTarget redirect)
        {
            switch (status)
            {
                case HttpStatus.MOVED_PERMANENTLY_301, HttpStatus.PERMANENT_REDIRECT_308 ->
                {
                    lock.writeLock().lock();
                    try
                    {
                        cache.put(original, redirect);
                    }
                    finally
                    {
                        lock.writeLock().unlock();
                    }
                }
                default ->
                {
                }
            }
        }

        @Override
        public int size()
        {
            lock.readLock().lock();
            try
            {
                return cache.size();
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public void clear()
        {
            lock.writeLock().lock();
            try
            {
                cache.clear();
            }
            finally
            {
                lock.writeLock().unlock();
            }
        }
    }
}

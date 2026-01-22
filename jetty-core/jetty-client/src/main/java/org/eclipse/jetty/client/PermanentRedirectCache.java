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

/**
 * <p>A cache for permanent HTTP redirects (301, 308).</p>
 * <p>When enabled, subsequent requests to previously-redirected URIs
 * skip the redirect round-trip by applying the cached redirect directly.</p>
 */
public interface PermanentRedirectCache
{
    /**
     * <p>Gets a cached redirect for the given normalized URI.</p>
     *
     * @param normalizedURI the normalized URI key
     * @return the cached redirect, or null if not found
     */
    CachedRedirect get(String normalizedURI);

    /**
     * <p>Caches a permanent redirect.</p>
     *
     * @param normalizedURI the normalized URI key
     * @param redirect the redirect to cache
     */
    void put(String normalizedURI, CachedRedirect redirect);

    /**
     * <p>Removes a cached redirect.</p>
     *
     * @param normalizedURI the normalized URI key
     * @return true if a redirect was removed
     */
    boolean remove(String normalizedURI);

    /**
     * <p>Clears all cached redirects.</p>
     */
    void clear();

    /**
     * @return the number of cached redirects
     */
    int size();

    /**
     * <p>Normalizes a request URI into a cache key.</p>
     * <p>The key includes scheme, host, port, path, and query.</p>
     *
     * @param request the request to normalize
     * @return the normalized URI string
     */
    static String normalizeURI(Request request)
    {
        String scheme = request.getScheme();
        String host = request.getHost();
        int port = request.getPort();
        port = HttpClient.normalizePort(scheme, port);
        String path = request.getPath();
        if (path == null || path.isEmpty())
            path = "/";
        String query = request.getQuery();

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port > 0)
            sb.append(":").append(port);
        sb.append(path);
        if (query != null && !query.isEmpty())
            sb.append("?").append(query);
        return sb.toString();
    }

    /**
     * <p>A cached redirect entry.</p>
     *
     * @param targetURI the target URI to redirect to
     * @param targetMethod the HTTP method to use for the redirected request
     * @param statusCode the original redirect status code (301 or 308)
     * @param creationTime the time this entry was created
     */
    record CachedRedirect(URI targetURI, String targetMethod, int statusCode, long creationTime) {}

    /**
     * <p>A no-op implementation that does not cache any redirects.</p>
     */
    class Empty implements PermanentRedirectCache
    {
        @Override
        public CachedRedirect get(String normalizedURI)
        {
            return null;
        }

        @Override
        public void put(String normalizedURI, CachedRedirect redirect)
        {
        }

        @Override
        public boolean remove(String normalizedURI)
        {
            return false;
        }

        @Override
        public void clear()
        {
        }

        @Override
        public int size()
        {
            return 0;
        }
    }

    /**
     * <p>Default implementation using a size-bounded LRU cache.</p>
     */
    class Default implements PermanentRedirectCache
    {
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Map<String, CachedRedirect> cache;

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
                protected boolean removeEldestEntry(Map.Entry<String, CachedRedirect> eldest)
                {
                    return size() > maxSize;
                }
            };
        }

        @Override
        public CachedRedirect get(String normalizedURI)
        {
            lock.readLock().lock();
            try
            {
                return cache.get(normalizedURI);
            }
            finally
            {
                lock.readLock().unlock();
            }
        }

        @Override
        public void put(String normalizedURI, CachedRedirect redirect)
        {
            lock.writeLock().lock();
            try
            {
                cache.put(normalizedURI, redirect);
            }
            finally
            {
                lock.writeLock().unlock();
            }
        }

        @Override
        public boolean remove(String normalizedURI)
        {
            lock.writeLock().lock();
            try
            {
                return cache.remove(normalizedURI) != null;
            }
            finally
            {
                lock.writeLock().unlock();
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
    }
}

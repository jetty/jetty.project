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

package org.eclipse.jetty.http;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>A container for {@link HttpCookie}s.</p>
 * <p>HTTP cookies are stored along with a {@link URI} via {@link #add(URI, HttpCookie)}
 * and retrieved via {@link #match(URI)}, which implements the path matching algorithm
 * defined by <a href="https://www.rfc-editor.org/rfc/rfc6265">RFC 6265</a>.</p>
 */
public interface HttpCookieStore
{
    /**
     * <p>Adds a cookie to this store, if possible.</p>
     * <p>The cookie may not be added for various reasons; for example,
     * it may be already expired, or its domain attribute does not
     * match that of the URI, etc.</p>
     * <p>The cookie is associated with the given {@code URI}, so that
     * a call to {@link #match(URI)} returns the cookie only if the
     * URIs match.</p>
     *
     * @param uri the {@code URI} associated with the cookie
     * @param cookie the cookie to add
     * @return whether the cookie has been added
     */
    public boolean add(URI uri, HttpCookie cookie);

    /**
     * @return all the cookies
     */
    public List<HttpCookie> all();

    /**
     * <p>Returns the cookies that match the given {@code URI}.</p>
     *
     * @param uri the {@code URI} to match against
     * @return a list of cookies that match the given {@code URI}
     */
    public List<HttpCookie> match(URI uri);

    /**
     * <p>Removes the cookie associated with the given {@code URI}.</p>
     *
     * @param uri the {@code URI} associated with the cookie to remove
     * @param cookie the cookie to remove
     * @return whether the cookie has been removed
     */
    public boolean remove(URI uri, HttpCookie cookie);

    /**
     * <p>Removes all the cookies from this store.</p>
     *
     * @return whether the store modified by this call
     */
    public boolean clear();

    /**
     * <p>An implementation of {@link HttpCookieStore} that does not store any cookie.</p>
     */
    public static class Empty implements HttpCookieStore
    {
        @Override
        public boolean add(URI uri, HttpCookie cookie)
        {
            return false;
        }

        @Override
        public List<HttpCookie> all()
        {
            return List.of();
        }

        @Override
        public List<HttpCookie> match(URI uri)
        {
            return List.of();
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie)
        {
            return false;
        }

        @Override
        public boolean clear()
        {
            return false;
        }
    }

    /**
     * <p>A default implementation of {@link HttpCookieStore}.</p>
     */
    public static class Default implements HttpCookieStore
    {
        private final AutoLock lock = new AutoLock();
        private final Map<String, List<StoredHttpCookie>> cookies = new HashMap<>();

        @Override
        public boolean add(URI uri, HttpCookie cookie)
        {
            // TODO: reject if cookie size is too big?

            String resolvedDomain = resolveDomain(uri, cookie);
            if (resolvedDomain == null)
                return false;

            String resolvedPath = resolvePath(uri, cookie);

            // Cookies are stored under their resolved domain, so that:
            // - add(sub.example.com, cookie[Domain]=null) => key=sub.example.com
            // - add(sub.example.com, cookie[Domain]=example.com) => key=example.com
            // This facilitates the matching algorithm.
            boolean[] added = new boolean[1];
            StoredHttpCookie storedCookie = new StoredHttpCookie(cookie, uri, resolvedDomain, resolvedPath);
            try (AutoLock ignored = lock.lock())
            {
                String key = resolvedDomain.toLowerCase(Locale.ENGLISH);
                cookies.compute(key, (k, v) ->
                {
                    // RFC 6265, section 4.1.2.
                    // Evict an existing cookie with
                    // same name, domain and path.
                    if (v != null)
                        v.remove(storedCookie);

                    // Add only non-expired cookies.
                    if (cookie.isExpired())
                        return v == null || v.isEmpty() ? null : v;

                    added[0] = true;
                    if (v == null)
                        v = new ArrayList<>();
                    v.add(storedCookie);
                    return v;
                });
            }

            return added[0];
        }

        private String resolveDomain(URI uri, HttpCookie cookie)
        {
            String uriDomain = uri.getHost();
            if (uriDomain == null)
                return null;

            String cookieDomain = cookie.getDomain();
            // No explicit cookie domain, use the origin domain.
            if (cookieDomain == null)
                return uriDomain;

            String resolvedDomain = cookieDomain;
            if (resolvedDomain.startsWith("."))
                resolvedDomain = cookieDomain.substring(1);
            // RFC 6265 section 4.1.2.3, ignore Domain if ends with ".".
            if (resolvedDomain.endsWith("."))
                resolvedDomain = uriDomain;
            // Reject top-level domains.
            // TODO: should also reject "top" domain such as co.uk, gov.au, etc.
            if (!resolvedDomain.contains("."))
            {
                if (!resolvedDomain.equalsIgnoreCase("localhost"))
                    return null;
            }

            // Reject if the resolved domain is not either
            // the same or a parent domain of the URI domain.
            if (!isSameOrSubDomain(uriDomain, resolvedDomain))
                return null;

            return resolvedDomain;
        }

        private String resolvePath(URI uri, HttpCookie cookie)
        {
            // RFC 6265, section 5.1.4 and 5.2.4.
            // Note that cookies with the Path attribute different from the
            // URI path are accepted, as specified in sections 8.5 and 8.6.
            String resolvedPath = cookie.getPath();
            if (resolvedPath == null || !resolvedPath.startsWith("/"))
            {
                String uriPath = uri.getRawPath();
                if (StringUtil.isBlank(uriPath) || !uriPath.startsWith("/"))
                {
                    resolvedPath = "/";
                }
                else
                {
                    int lastSlash = uriPath.lastIndexOf('/');
                    resolvedPath = uriPath.substring(0, lastSlash);
                    if (resolvedPath.isEmpty())
                        resolvedPath = "/";
                }
            }
            return resolvedPath;
        }

        @Override
        public List<HttpCookie> all()
        {
            try (AutoLock ignored = lock.lock())
            {
                return cookies.values().stream()
                    .flatMap(Collection::stream)
                    .filter(Predicate.not(StoredHttpCookie::isExpired))
                    .map(HttpCookie.class::cast)
                    .toList();
            }
        }

        @Override
        public List<HttpCookie> match(URI uri)
        {
            String uriDomain = uri.getHost();
            if (uriDomain == null)
                return List.of();

            String path = uri.getRawPath();
            if (path == null || path.isBlank())
                path = "/";

            boolean secure = HttpScheme.isSecure(uri.getScheme());

            List<HttpCookie> result = new ArrayList<>();
            try (AutoLock ignored = lock.lock())
            {
                // Given the way cookies are stored in the Map, the matching
                // algorithm starts with the URI domain and iterates chopping
                // its subdomains, accumulating the results.
                // For example, for uriDomain = sub.example.com, the cookies
                // Map is accessed with the following Keys:
                // - Key[domain=sub.example.com]
                // - chop domain to example.com
                // - Key[domain=example.com]
                // - chop domain to com
                //   invalid domain, exit iteration.
                String domain = uriDomain.toLowerCase(Locale.ENGLISH);
                while (domain != null)
                {
                    List<StoredHttpCookie> stored = cookies.get(domain);
                    Iterator<StoredHttpCookie> iterator = stored == null ? Collections.emptyIterator() : stored.iterator();
                    while (iterator.hasNext())
                    {
                        StoredHttpCookie cookie = iterator.next();

                        // Check and remove expired cookies.
                        if (cookie.isExpired())
                        {
                            iterator.remove();
                            continue;
                        }

                        // Check whether the cookie is secure.
                        if (cookie.isSecure() && !secure)
                            continue;

                        // Match the domain.
                        if (!domainMatches(uriDomain, cookie.domain, cookie.getWrapped().getDomain()))
                            continue;

                        // Match the path.
                        if (!pathMatches(path, cookie.path))
                            continue;

                        result.add(cookie);
                    }
                    domain = parentDomain(domain);
                }
            }

            return result;
        }

        private static boolean domainMatches(String uriDomain, String domain, String cookieDomain)
        {
            // If the cookie has no domain, or ends with ".", it must only be sent to the origin domain.
            if (cookieDomain == null || cookieDomain.endsWith("."))
                return uriDomain.equalsIgnoreCase(domain);
            return isSameOrSubDomain(uriDomain, cookieDomain);
        }

        private static boolean isSameOrSubDomain(String subDomain, String domain)
        {
            int subDomainLength = subDomain.length();
            int domainLength = domain.length();
            // Case-insensitive version of subDomain.endsWith(domain).
            if (!subDomain.regionMatches(true, subDomainLength - domainLength, domain, 0, domainLength))
                return false;
            // Make sure it is a subdomain.
            int beforeMatch = subDomainLength - domainLength - 1;
            // Domains are the same.
            if (beforeMatch < 0)
                return true;
            // Verify it is a proper subdomain such as bar.foo.com,
            // not just a suffix of a domain such as bazfoo.com.
            return subDomain.charAt(beforeMatch) == '.';
        }

        private static boolean pathMatches(String uriPath, String cookiePath)
        {
            if (cookiePath == null)
                return true;
            // RFC 6265, section 5.1.4, path matching algorithm.
            if (uriPath.equals(cookiePath))
                return true;
            if (uriPath.startsWith(cookiePath))
                return cookiePath.endsWith("/") || uriPath.charAt(cookiePath.length()) == '/';
            return false;
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie)
        {
            String uriDomain = uri.getHost();
            if (uriDomain == null)
                return false;

            String resolvedPath = resolvePath(uri, cookie);

            boolean[] removed = new boolean[1];
            try (AutoLock ignored = lock.lock())
            {
                String domain = uriDomain.toLowerCase(Locale.ENGLISH);
                while (domain != null)
                {
                    cookies.compute(domain, (k, v) ->
                    {
                        if (v == null)
                            return null;

                        Iterator<StoredHttpCookie> iterator = v.iterator();
                        while (iterator.hasNext())
                        {
                            StoredHttpCookie storedCookie = iterator.next();
                            if (uriDomain.equalsIgnoreCase(storedCookie.uri.getHost()))
                            {
                                if (storedCookie.path.equals(resolvedPath))
                                {
                                    if (storedCookie.getWrapped().getName().equals(cookie.getName()))
                                    {
                                        iterator.remove();
                                        removed[0] = true;
                                    }
                                }
                            }
                        }

                        return v.isEmpty() ? null : v;
                    });
                    domain = parentDomain(domain);
                }
            }
            return removed[0];
        }

        private String parentDomain(String domain)
        {
            int dot = domain.indexOf('.');
            if (dot < 0)
                return null;
            // Remove one subdomain.
            domain = domain.substring(dot + 1);
            // Exit if the top-level domain was reached.
            if (domain.indexOf('.') < 0)
                return null;
            return domain;
        }

        @Override
        public boolean clear()
        {
            try (AutoLock ignored = lock.lock())
            {
                if (cookies.isEmpty())
                    return false;
                cookies.clear();
                return true;
            }
        }

        private static class StoredHttpCookie extends HttpCookie.Wrapper
        {
            private final long creationNanoTime = NanoTime.now();
            private final URI uri;
            private final String domain;
            private final String path;

            private StoredHttpCookie(HttpCookie wrapped, URI uri, String domain, String path)
            {
                super(wrapped);
                this.uri = Objects.requireNonNull(uri);
                this.domain = Objects.requireNonNull(domain);
                this.path = Objects.requireNonNull(path);
            }

            @Override
            public boolean isExpired()
            {
                long maxAge = getMaxAge();
                if (maxAge >= 0 && NanoTime.secondsSince(creationNanoTime) > maxAge)
                    return true;
                Instant expires = getExpires();
                return expires != null && Instant.now().isAfter(expires);
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(getWrapped().getName(), domain.toLowerCase(Locale.ENGLISH), path);
            }

            @Override
            public boolean equals(Object obj)
            {
                if (this == obj)
                    return true;
                if (!(obj instanceof StoredHttpCookie that))
                    return false;
                return getName().equals(that.getName()) &&
                       domain.equalsIgnoreCase(that.domain) &&
                       path.equals(that.path);
            }
        }
    }
}

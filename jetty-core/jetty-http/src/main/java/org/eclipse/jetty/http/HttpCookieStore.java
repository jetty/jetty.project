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

import org.eclipse.jetty.util.NanoTime;
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
        private final Map<Key, List<HttpCookie>> cookies = new HashMap<>();

        @Override
        public boolean add(URI uri, HttpCookie cookie)
        {
            // TODO: reject if cookie size is too big?

            boolean secure = HttpScheme.isSecure(uri.getScheme());
            // Do not accept a secure cookie sent over an insecure channel.
            if (cookie.isSecure() && !secure)
                return false;

            String cookieDomain = cookie.getDomain();
            if (cookieDomain != null)
            {
                cookieDomain = cookieDomain.toLowerCase(Locale.ENGLISH);
                if (cookieDomain.startsWith("."))
                    cookieDomain = cookieDomain.substring(1);
                // RFC 6265 section 4.1.2.3, ignore Domain if ends with ".".
                if (cookieDomain.endsWith("."))
                    cookieDomain = uri.getHost();
                // Reject top-level domains.
                // TODO: should also reject "top" domain such as co.uk, gov.au, etc.
                if (!cookieDomain.contains("."))
                {
                    if (!cookieDomain.equals("localhost"))
                        return false;
                }

                String domain = uri.getHost();
                if (domain != null)
                {
                    domain = domain.toLowerCase(Locale.ENGLISH);
                    // If uri.host==foo.example.com, only accept
                    // cookie.domain==(foo.example.com|example.com).
                    if (!domain.endsWith(cookieDomain))
                        return false;
                    int beforeMatch = domain.length() - cookieDomain.length() - 1;
                    if (beforeMatch >= 0 && domain.charAt(beforeMatch) != '.')
                        return false;
                }
            }
            else
            {
                // No explicit cookie domain, use the origin domain.
                cookieDomain = uri.getHost();
            }

            // Cookies are stored under their domain, so that:
            // - add(sub.example.com, cookie[Domain]=null) => Key[domain=sub.example.com]
            // - add(sub.example.com, cookie[Domain]=example.com) => Key[domain=example.com]
            // This facilitates the matching algorithm.
            Key key = new Key(secure, cookieDomain);
            boolean[] result = new boolean[]{true};
            try (AutoLock ignored = lock.lock())
            {
                cookies.compute(key, (k, v) ->
                {
                    // RFC 6265, section 4.1.2.
                    // Evict an existing cookie with
                    // same name, domain and path.
                    if (v != null)
                        v.remove(cookie);

                    // Add only non-expired cookies.
                    if (cookie.isExpired())
                    {
                        result[0] = false;
                        return v == null || v.isEmpty() ? null : v;
                    }

                    if (v == null)
                        v = new ArrayList<>();
                    v.add(new Cookie(cookie));
                    return v;
                });
            }

            return result[0];
        }

        @Override
        public List<HttpCookie> all()
        {
            try (AutoLock ignored = lock.lock())
            {
                return cookies.values().stream()
                    .flatMap(Collection::stream)
                    .toList();
            }
        }

        @Override
        public List<HttpCookie> match(URI uri)
        {
            List<HttpCookie> result = new ArrayList<>();
            boolean secure = HttpScheme.isSecure(uri.getScheme());
            String uriDomain = uri.getHost();
            String path = uri.getPath();
            if (path == null || path.trim().isEmpty())
                path = "/";

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
                String domain = uriDomain;
                while (true)
                {
                    Key key = new Key(secure, domain);
                    List<HttpCookie> stored = cookies.get(key);
                    Iterator<HttpCookie> iterator = stored == null ? Collections.emptyIterator() : stored.iterator();
                    while (iterator.hasNext())
                    {
                        HttpCookie cookie = iterator.next();

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
                        if (!domainMatches(uriDomain, key.domain, cookie.getDomain()))
                            continue;

                        // Match the path.
                        if (!pathMatches(path, cookie.getPath()))
                            continue;

                        result.add(cookie);
                    }

                    int dot = domain.indexOf('.');
                    if (dot < 0)
                        break;
                    // Remove one subdomain.
                    domain = domain.substring(dot + 1);
                    // Exit if the top-level domain was reached.
                    if (domain.indexOf('.') < 0)
                        break;
                }
            }

            return result;
        }

        private static boolean domainMatches(String uriDomain, String domain, String cookieDomain)
        {
            if (uriDomain == null)
                return true;
            if (domain != null)
                domain = domain.toLowerCase(Locale.ENGLISH);
            uriDomain = uriDomain.toLowerCase(Locale.ENGLISH);
            if (cookieDomain != null)
                cookieDomain = cookieDomain.toLowerCase(Locale.ENGLISH);
            if (cookieDomain == null || cookieDomain.endsWith("."))
            {
                // RFC 6265, section 4.1.2.3.
                // No cookie domain -> cookie sent only to origin server.
                return uriDomain.equals(domain);
            }
            if (cookieDomain.startsWith("."))
                cookieDomain = cookieDomain.substring(1);
            if (uriDomain.endsWith(cookieDomain))
            {
                // The domain is the same as, or a subdomain of, the cookie domain.
                int beforeMatch = uriDomain.length() - cookieDomain.length() - 1;
                // Domains are the same.
                if (beforeMatch == -1)
                    return true;
                // Verify it is a proper subdomain such as bar.foo.com,
                // not just a suffix of a domain such as bazfoo.com.
                return uriDomain.charAt(beforeMatch) == '.';
            }
            return false;
        }

        private static boolean pathMatches(String path, String cookiePath)
        {
            if (cookiePath == null)
                return true;
            // RFC 6265, section 5.1.4, path matching algorithm.
            if (path.equals(cookiePath))
                return true;
            if (path.startsWith(cookiePath))
                return cookiePath.endsWith("/") || path.charAt(cookiePath.length()) == '/';
            return false;
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie)
        {
            Key key = new Key(HttpScheme.isSecure(uri.getScheme()), uri.getHost());
            try (AutoLock ignored = lock.lock())
            {
                boolean[] result = new boolean[1];
                cookies.compute(key, (k, v) ->
                {
                    if (v == null)
                        return null;
                    boolean removed = v.remove(cookie);
                    result[0] = removed;
                    return v.isEmpty() ? null : v;
                });
                return result[0];
            }
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

        private record Key(boolean secure, String domain)
        {
            private Key(boolean secure, String domain)
            {
                this.secure = secure;
                this.domain = domain.toLowerCase(Locale.ENGLISH);
            }
        }

        private static class Cookie extends HttpCookie.Wrapper
        {
            private final long creationNanoTime = NanoTime.now();

            public Cookie(HttpCookie wrapped)
            {
                super(wrapped);
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
        }
    }
}

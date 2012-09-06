//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.client.api.CookieStore;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.http.HttpCookie;

public class HttpCookieStore implements CookieStore
{
    private final ConcurrentMap<String, Map<String, HttpCookie>> allCookies = new ConcurrentHashMap<>();

    @Override
    public List<HttpCookie> getCookies(Destination destination, String path)
    {
        List<HttpCookie> result = new ArrayList<>();

        String host = destination.host();
        int port = destination.port();
        String key = host + ":" + port + path;

        // First lookup: direct hit
        Map<String, HttpCookie> cookies = allCookies.get(key);
        if (cookies != null)
            accumulateCookies(destination, cookies, result);

        // Second lookup: root path
        if (!"/".equals(path))
        {
            key = host + ":" + port + "/";
            cookies = allCookies.get(key);
            if (cookies != null)
                accumulateCookies(destination, cookies, result);
        }

        // Third lookup: parent domains
        int domains = host.split("\\.").length - 1;
        for (int i = 2; i <= domains; ++i)
        {
            String[] hostParts = host.split("\\.", i);
            host = hostParts[hostParts.length - 1];
            key = host + ":" + port + "/";
            cookies = allCookies.get(key);
            if (cookies != null)
                accumulateCookies(destination, cookies, result);
        }

        return result;
    }

    private void accumulateCookies(Destination destination, Map<String, HttpCookie> cookies, List<HttpCookie> result)
    {
        for (Iterator<HttpCookie> iterator = cookies.values().iterator(); iterator.hasNext(); )
        {
            HttpCookie cookie = iterator.next();
            if (cookie.isExpired(System.nanoTime()))
            {
                iterator.remove();
            }
            else
            {
                if (!"https".equalsIgnoreCase(destination.scheme()) && cookie.isSecure())
                    continue;
                result.add(cookie);
            }
        }
    }

    @Override
    public boolean addCookie(Destination destination, HttpCookie cookie)
    {
        String destinationDomain = destination.host() + ":" + destination.port();

        // Check whether it is the same domain
        String domain = cookie.getDomain();
        if (domain == null)
            domain = destinationDomain;

        if (domain.indexOf(':') < 0)
            domain += ":" + ("https".equalsIgnoreCase(destination.scheme()) ? 443 : 80);

        // Cookie domains may start with a ".", like ".domain.com"
        // This also avoids that a request to sub.domain.com sets a cookie for domain.com
        if (!domain.endsWith(destinationDomain))
            return false;

        // Normalize the path
        String path = cookie.getPath();
        if (path == null || path.length() == 0)
            path = "/";

        String key = destination.host() + ":" + destination.port() + path;
        Map<String, HttpCookie> cookies = allCookies.get(key);
        if (cookies == null)
        {
            cookies = new ConcurrentHashMap<>();
            Map<String, HttpCookie> existing = allCookies.putIfAbsent(key, cookies);
            if (existing != null)
                cookies = existing;
        }
        cookies.put(path, cookie);
        return true;
    }
}

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

package org.eclipse.jetty.ee9.servlets;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.PushBuilder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A filter that builds a cache of secondary resources associated
 * to primary resources.</p>
 * <p>A typical request for a primary resource such as {@code index.html}
 * is immediately followed by a number of requests for secondary resources.
 * Secondary resource requests will have a {@code Referer} HTTP header
 * that points to {@code index.html}, which is used to associate the secondary
 * resource to the primary resource.</p>
 * <p>Only secondary resources that are requested within a (small) time period
 * from the request of the primary resource are associated with the primary
 * resource.</p>
 * <p>This allows to build a cache of secondary resources associated with
 * primary resources. When a request for a primary resource arrives, associated
 * secondary resources are pushed to the client, unless the request carries
 * {@code If-xxx} header that hint that the client has the resources in its
 * cache.</p>
 * <p>If the init param useQueryInKey is set, then the query string is used as
 * as part of the key to identify a resource</p>
 */
@ManagedObject("Push cache based on the HTTP 'Referer' header")
public class PushCacheFilter implements Filter
{
    private static final Logger LOG = LoggerFactory.getLogger(PushCacheFilter.class);

    private final Set<Integer> _ports = new HashSet<>();
    private final Set<String> _hosts = new HashSet<>();
    private final ConcurrentMap<String, PrimaryResource> _cache = new ConcurrentHashMap<>();
    private long _associatePeriod = 4000L;
    private int _maxAssociations = 16;
    private long _renew = NanoTime.now();
    private boolean _useQueryInKey;

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        String associatePeriod = config.getInitParameter("associatePeriod");
        if (associatePeriod != null)
            _associatePeriod = Long.parseLong(associatePeriod);

        String maxAssociations = config.getInitParameter("maxAssociations");
        if (maxAssociations != null)
            _maxAssociations = Integer.parseInt(maxAssociations);

        String hosts = config.getInitParameter("hosts");
        if (hosts != null)
            Collections.addAll(_hosts, StringUtil.csvSplit(hosts));

        String ports = config.getInitParameter("ports");
        if (ports != null)
            for (String p : StringUtil.csvSplit(ports))
            {
                _ports.add(Integer.parseInt(p));
            }

        _useQueryInKey = Boolean.parseBoolean(config.getInitParameter("useQueryInKey"));

        // Expose for JMX.
        config.getServletContext().setAttribute(config.getFilterName(), this);

        if (LOG.isDebugEnabled())
            LOG.debug("period={} max={} hosts={} ports={}", _associatePeriod, _maxAssociations, _hosts, _ports);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException
    {
        HttpServletRequest request = (HttpServletRequest)req;

        PushBuilder pushBuilder = request.newPushBuilder();
        if (HttpVersion.fromString(request.getProtocol()).getVersion() < 20 ||
            !HttpMethod.GET.is(request.getMethod()) ||
            pushBuilder == null)
        {
            chain.doFilter(req, resp);
            return;
        }

        long now = NanoTime.now();

        boolean conditional = false;
        String referrer = null;
        List<String> headerNames = Collections.list(request.getHeaderNames());
        for (String headerName : headerNames)
        {
            if (HttpHeader.IF_MATCH.is(headerName) ||
                HttpHeader.IF_MODIFIED_SINCE.is(headerName) ||
                HttpHeader.IF_NONE_MATCH.is(headerName) ||
                HttpHeader.IF_UNMODIFIED_SINCE.is(headerName))
            {
                conditional = true;
                break;
            }
            else if (HttpHeader.REFERER.is(headerName))
            {
                referrer = request.getHeader(headerName);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} referrer={} conditional={}", request.getMethod(), request.getRequestURI(), referrer, conditional);

        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (_useQueryInKey && query != null)
            path += "?" + query;
        if (referrer != null)
        {
            HttpURI referrerURI = HttpURI.from(referrer);
            String host = referrerURI.getHost();
            int port = referrerURI.getPort();
            if (port <= 0)
            {
                String scheme = referrerURI.getScheme();
                if (scheme != null)
                    port = HttpScheme.HTTPS.is(scheme) ? 443 : 80;
                else
                    port = request.isSecure() ? 443 : 80;
            }

            boolean referredFromHere = !_hosts.isEmpty() ? _hosts.contains(host) : host.equals(request.getServerName());
            referredFromHere &= !_ports.isEmpty() ? _ports.contains(port) : port == request.getServerPort();

            if (referredFromHere)
            {
                if (HttpMethod.GET.is(request.getMethod()))
                {
                    String referrerPath = _useQueryInKey ? referrerURI.getPathQuery() : referrerURI.getPath();
                    if (referrerPath == null)
                        referrerPath = "/";
                    if (referrerPath.startsWith(request.getContextPath() + "/"))
                    {
                        if (!referrerPath.equals(path))
                        {
                            PrimaryResource primaryResource = _cache.get(referrerPath);
                            if (primaryResource != null)
                            {
                                long primaryNanoTime = primaryResource._nanoTime.get();
                                if (primaryNanoTime != 0)
                                {
                                    if (NanoTime.millisElapsed(primaryNanoTime, now) < _associatePeriod)
                                    {
                                        Set<String> associated = primaryResource._associated;
                                        // Not strictly concurrent-safe, just best effort to limit associations.
                                        if (associated.size() <= _maxAssociations)
                                        {
                                            if (associated.add(path))
                                            {
                                                if (LOG.isDebugEnabled())
                                                    LOG.debug("Associated {} to {}", path, referrerPath);
                                            }
                                        }
                                        else
                                        {
                                            if (LOG.isDebugEnabled())
                                                LOG.debug("Not associated {} to {}, exceeded max associations of {}", path, referrerPath, _maxAssociations);
                                        }
                                    }
                                    else
                                    {
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("Not associated {} to {}, outside associate period of {}ms", path, referrerPath, _associatePeriod);
                                    }
                                }
                            }
                        }
                        else
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Not associated {} to {}, referring to self", path, referrerPath);
                        }
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Not associated {} to {}, different context", path, referrerPath);
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("External referrer {}", referrer);
            }
        }

        PrimaryResource primaryResource = _cache.get(path);
        if (primaryResource == null)
        {
            PrimaryResource r = new PrimaryResource();
            primaryResource = _cache.putIfAbsent(path, r);
            primaryResource = primaryResource == null ? r : primaryResource;
            primaryResource._nanoTime.compareAndSet(0, now);
            if (LOG.isDebugEnabled())
                LOG.debug("Cached primary resource {}", path);
        }
        else
        {
            long last = primaryResource._nanoTime.get();
            if (NanoTime.isBefore(last, _renew) && primaryResource._nanoTime.compareAndSet(last, now))
            {
                primaryResource._associated.clear();
                if (LOG.isDebugEnabled())
                    LOG.debug("Clear associated resources for {}", path);
            }
        }

        // Push associated resources.
        if (!conditional && !primaryResource._associated.isEmpty())
        {
            // Breadth-first push of associated resources.
            Queue<PrimaryResource> queue = new ArrayDeque<>();
            queue.offer(primaryResource);
            while (!queue.isEmpty())
            {
                PrimaryResource parent = queue.poll();
                for (String childPath : parent._associated)
                {
                    PrimaryResource child = _cache.get(childPath);
                    if (child != null)
                        queue.offer(child);

                    if (LOG.isDebugEnabled())
                        LOG.debug("Pushing {} for {}", childPath, path);
                    pushBuilder.path(childPath).push();
                }
            }
        }

        chain.doFilter(request, resp);
    }

    @Override
    public void destroy()
    {
        clearPushCache();
    }

    @ManagedAttribute("The push cache contents")
    public Map<String, String> getPushCache()
    {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, PrimaryResource> entry : _cache.entrySet())
        {
            PrimaryResource resource = entry.getValue();
            String value = String.format("size=%d: %s", resource._associated.size(), new TreeSet<>(resource._associated));
            result.put(entry.getKey(), value);
        }
        return result;
    }

    @ManagedOperation(value = "Renews the push cache contents", impact = "ACTION")
    public void renewPushCache()
    {
        _renew = NanoTime.now();
    }

    @ManagedOperation(value = "Clears the push cache contents", impact = "ACTION")
    public void clearPushCache()
    {
        _cache.clear();
    }

    private static class PrimaryResource
    {
        private final Set<String> _associated = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final AtomicLong _nanoTime = new AtomicLong();
    }
}

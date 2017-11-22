//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.PushBuilder;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
    private static final Logger LOG = Log.getLogger(PushCacheFilter.class);

    private final Set<Integer> _ports = new HashSet<>();
    private final Set<String> _hosts = new HashSet<>();
    private final ConcurrentMap<String, PrimaryResource> _cache = new ConcurrentHashMap<>();
    private long _associatePeriod = 4000L;
    private int _maxAssociations = 16;
    private long _renew = System.nanoTime();
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
                _ports.add(Integer.parseInt(p));

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
        Request jettyRequest = Request.getBaseRequest(request);

        if (HttpVersion.fromString(request.getProtocol()).getVersion() < 20 ||
                !HttpMethod.GET.is(request.getMethod()) ||
                !jettyRequest.isPushSupported())
        {
            chain.doFilter(req, resp);
            return;
        }

        long now = System.nanoTime();

        // Iterating over fields is more efficient than multiple gets
        HttpFields fields = jettyRequest.getHttpFields();
        boolean conditional = false;
        String referrer = null;
        loop:
        for (int i = 0; i < fields.size(); i++)
        {
            HttpField field = fields.getField(i);
            HttpHeader header = field.getHeader();
            if (header == null)
                continue;

            switch (header)
            {
                case IF_MATCH:
                case IF_MODIFIED_SINCE:
                case IF_NONE_MATCH:
                case IF_UNMODIFIED_SINCE:
                    conditional = true;
                    break loop;

                case REFERER:
                    referrer = field.getValue();
                    break;

                default:
                    break;
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
            HttpURI referrerURI = new HttpURI(referrer);
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

            boolean referredFromHere = _hosts.size() > 0 ? _hosts.contains(host) : host.equals(request.getServerName());
            referredFromHere &= _ports.size() > 0 ? _ports.contains(port) : port == request.getServerPort();

            if (referredFromHere)
            {
                if (HttpMethod.GET.is(request.getMethod()))
                {
                    String referrerPath = _useQueryInKey?referrerURI.getPathQuery():referrerURI.getPath();
                    if (referrerPath == null)
                        referrerPath = "/";
                    if (referrerPath.startsWith(request.getContextPath() + "/"))
                    {
                        if (!referrerPath.equals(path))
                        {
                            PrimaryResource primaryResource = _cache.get(referrerPath);
                            if (primaryResource != null)
                            {
                                long primaryTimestamp = primaryResource._timestamp.get();
                                if (primaryTimestamp != 0)
                                {
                                    if (now - primaryTimestamp < TimeUnit.MILLISECONDS.toNanos(_associatePeriod))
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
            primaryResource._timestamp.compareAndSet(0, now);
            if (LOG.isDebugEnabled())
                LOG.debug("Cached primary resource {}", path);
        }
        else
        {
            long last = primaryResource._timestamp.get();
            if (last < _renew && primaryResource._timestamp.compareAndSet(last, now))
            {
                primaryResource._associated.clear();
                if (LOG.isDebugEnabled())
                    LOG.debug("Clear associated resources for {}", path);
            }
        }

        // Push associated resources.
        if (!conditional && !primaryResource._associated.isEmpty())
        {
            PushBuilder pushBuilder = jettyRequest.getPushBuilder();

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
        _renew = System.nanoTime();
    }

    @ManagedOperation(value = "Clears the push cache contents", impact = "ACTION")
    public void clearPushCache()
    {
        _cache.clear();
    }

    private static class PrimaryResource
    {
        private final Set<String> _associated = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final AtomicLong _timestamp = new AtomicLong();
    }
}

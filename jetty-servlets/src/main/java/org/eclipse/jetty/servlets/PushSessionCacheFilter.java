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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.PushBuilder;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PushSessionCacheFilter implements Filter
{
    private static final String RESPONSE_ATTR = "PushSessionCacheFilter.response";
    private static final String TARGET_ATTR = "PushSessionCacheFilter.target";
    private static final String TIMESTAMP_ATTR = "PushSessionCacheFilter.timestamp";
    private static final Logger LOG = LoggerFactory.getLogger(PushSessionCacheFilter.class);
    private final ConcurrentMap<String, Target> _cache = new ConcurrentHashMap<>();
    private long _associateDelay = 5000L;

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        if (config.getInitParameter("associateDelay") != null)
            _associateDelay = Long.parseLong(config.getInitParameter("associateDelay"));

        // Add a listener that is used to collect information
        // about associated resource, etags and modified dates.
        config.getServletContext().addListener(new ServletRequestListener()
        {
            // Collect information when request is destroyed.
            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                HttpServletRequest request = (HttpServletRequest)sre.getServletRequest();
                Target target = (Target)request.getAttribute(TARGET_ATTR);
                if (target == null)
                    return;

                // Update conditional data.
                HttpServletResponse response = (HttpServletResponse)request.getAttribute(RESPONSE_ATTR);
                target._etag = response.getHeader(HttpHeader.ETAG.asString());
                target._lastModified = response.getHeader(HttpHeader.LAST_MODIFIED.asString());

                if (LOG.isDebugEnabled())
                    LOG.debug("Served {} for {}", response.getStatus(), request.getRequestURI());

                // Does this request have a referer?
                String referer = request.getHeader(HttpHeader.REFERER.asString());
                if (referer != null)
                {
                    // Is the referer from this contexts?
                    HttpURI refererUri = HttpURI.from(referer);
                    if (request.getServerName().equals(refererUri.getHost()))
                    {
                        Target refererTarget = _cache.get(refererUri.getPath());
                        if (refererTarget != null)
                        {
                            HttpSession session = request.getSession();
                            @SuppressWarnings("unchecked")
                            ConcurrentHashMap<String, Long> timestamps = (ConcurrentHashMap<String, Long>)session.getAttribute(TIMESTAMP_ATTR);
                            Long last = timestamps.get(refererTarget._path);
                            if (last != null && NanoTime.millisSince(last) < _associateDelay)
                            {
                                if (refererTarget._associated.putIfAbsent(target._path, target) == null)
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("ASSOCIATE {}->{}", refererTarget._path, target._path);
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void requestInitialized(ServletRequestEvent sre)
            {
            }
        });
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException
    {
        req.setAttribute(RESPONSE_ATTR, resp);
        HttpServletRequest request = (HttpServletRequest)req;
        String uri = request.getRequestURI();

        if (LOG.isDebugEnabled())
            LOG.debug("{} {}", request.getMethod(), uri);

        HttpSession session = request.getSession(true);

        // find the target for this resource
        Target target = _cache.get(uri);
        if (target == null)
        {
            Target t = new Target(uri);
            target = _cache.putIfAbsent(uri, t);
            target = target == null ? t : target;
        }
        request.setAttribute(TARGET_ATTR, target);

        // Set the timestamp for this resource in this session
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> timestamps = (ConcurrentHashMap<String, Long>)session.getAttribute(TIMESTAMP_ATTR);
        if (timestamps == null)
        {
            timestamps = new ConcurrentHashMap<>();
            session.setAttribute(TIMESTAMP_ATTR, timestamps);
        }
        timestamps.put(uri, NanoTime.now());

        // Push any associated resources.
        PushBuilder builder = request.newPushBuilder();
        if (builder != null && !target._associated.isEmpty())
        {
            boolean conditional = request.getHeader(HttpHeader.IF_NONE_MATCH.asString()) != null ||
                request.getHeader(HttpHeader.IF_MODIFIED_SINCE.asString()) != null;
            // Breadth-first push of associated resources.
            Queue<Target> queue = new ArrayDeque<>();
            queue.offer(target);
            while (!queue.isEmpty())
            {
                Target parent = queue.poll();
                builder.addHeader("X-Pusher", PushSessionCacheFilter.class.toString());
                for (Target child : parent._associated.values())
                {
                    queue.offer(child);

                    String path = child._path;
                    if (LOG.isDebugEnabled())
                        LOG.debug("PUSH {} <- {}", path, uri);

                    builder.path(path)
                        .setHeader(HttpHeader.IF_NONE_MATCH.asString(), conditional ? child._etag : null)
                        .setHeader(HttpHeader.IF_MODIFIED_SINCE.asString(), conditional ? child._lastModified : null);
                }
            }
        }

        chain.doFilter(req, resp);
    }

    @Override
    public void destroy()
    {
        _cache.clear();
    }

    private static class Target
    {
        private final String _path;
        private final ConcurrentMap<String, Target> _associated = new ConcurrentHashMap<>();
        private volatile String _etag;
        private volatile String _lastModified;

        private Target(String path)
        {
            _path = path;
        }

        @Override
        public String toString()
        {
            return String.format("Target{p=%s,e=%s,m=%s,a=%d}", _path, _etag, _lastModified, _associated.size());
        }
    }
}

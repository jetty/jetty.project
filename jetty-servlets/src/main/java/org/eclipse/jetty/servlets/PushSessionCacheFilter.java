//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.PushBuilder;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class PushSessionCacheFilter implements Filter
{
    private static final String TARGET_ATTR = "PushCacheFilter.target";
    private static final String TIMESTAMP_ATTR = "PushCacheFilter.timestamp";
    private static final Logger LOG = Log.getLogger(PushSessionCacheFilter.class);
    private final ConcurrentMap<String, Target> _cache = new ConcurrentHashMap<>();
    private long _associateDelay = 5000L;

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        if (config.getInitParameter("associateDelay") != null)
            _associateDelay = Long.parseLong(config.getInitParameter("associateDelay"));

        // Add a listener that is used to collect information about associated resource,
        // etags and modified dates
        config.getServletContext().addListener(new ServletRequestListener()
        {
            // Collect information when request is destroyed.
            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                Request request = Request.getBaseRequest(sre.getServletRequest());
                Target target = (Target)request.getAttribute(TARGET_ATTR);
                if (target == null)
                    return;

                // Update conditional data
                Response response = request.getResponse();
                target._etag = response.getHttpFields().get(HttpHeader.ETAG);
                target._lastModified = response.getHttpFields().get(HttpHeader.LAST_MODIFIED);

                // Don't associate pushes
                if (request.isPush())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Pushed {} for {}", request.getResponse().getStatus(), request.getRequestURI());
                    return;
                }
                else if (LOG.isDebugEnabled())
                {
                    LOG.debug("Served {} for {}", request.getResponse().getStatus(), request.getRequestURI());
                }

                // Does this request have a referer?
                String referer = request.getHttpFields().get(HttpHeader.REFERER);

                if (referer != null)
                {
                    // Is the referer from this contexts?
                    HttpURI refererUri = new HttpURI(referer);
                    if (request.getServerName().equals(refererUri.getHost()))
                    {
                        Target refererTarget = _cache.get(refererUri.getPath());
                        if (refererTarget != null)
                        {
                            HttpSession session = request.getSession();
                            ConcurrentHashMap<String, Long> timestamps = (ConcurrentHashMap<String, Long>)session.getAttribute(TIMESTAMP_ATTR);
                            Long last = timestamps.get(refererTarget._path);
                            if (last != null && (System.currentTimeMillis() - last) < _associateDelay)
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
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        // Get Jetty request as these APIs are not yet standard
        Request baseRequest = Request.getBaseRequest(request);
        String uri = baseRequest.getRequestURI();

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} push={}", baseRequest.getMethod(), uri, baseRequest.isPush());

        HttpSession session = baseRequest.getSession(true);

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
        ConcurrentHashMap<String, Long> timestamps = (ConcurrentHashMap<String, Long>)session.getAttribute(TIMESTAMP_ATTR);
        if (timestamps == null)
        {
            timestamps = new ConcurrentHashMap<>();
            session.setAttribute(TIMESTAMP_ATTR, timestamps);
        }
        timestamps.put(uri, System.currentTimeMillis());

        // push any associated resources
        if (baseRequest.isPushSupported() && !baseRequest.isPush() && !target._associated.isEmpty())
        {
            // Breadth-first push of associated resources.
            Queue<Target> queue = new ArrayDeque<>();
            queue.offer(target);
            while (!queue.isEmpty())
            {
                Target parent = queue.poll();
                PushBuilder builder = baseRequest.getPushBuilder();
                builder.addHeader("X-Pusher", PushSessionCacheFilter.class.toString());
                for (Target child : parent._associated.values())
                {
                    queue.offer(child);

                    String path = child._path;
                    if (LOG.isDebugEnabled())
                        LOG.debug("PUSH {} <- {}", path, uri);

                    builder.path(path).etag(child._etag).lastModified(child._lastModified).push();
                }
            }
        }

        chain.doFilter(request, response);
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

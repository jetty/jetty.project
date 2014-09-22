//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A filter that builds a cache of associated resources to push
 * using the following heuristics:<ul>
 * <li>If a request has a If-xxx header, this suggests it's cache is already hot,
 * so no resources are pushed.
 * <li>If a request has a referrer header that matches this site, then
 * this indicates that it is an associated resource
 * <li>If the time period between a request and an associated request is small,
 * that indicates a possible push resource
 * </ul>
 */
@ManagedObject("Push cache based on the HTTP 'Referer' header")
public class PushCacheFilter implements Filter
{
    private static final Logger LOG = Log.getLogger(PushCacheFilter.class);

    private final ConcurrentMap<String, PrimaryResource> _cache = new ConcurrentHashMap<>();
    private long _associatePeriod = 4000L;
    private volatile long _renew = System.nanoTime();
    private String _renewPath = "/__renewPushCache__";
    private final Set<Integer> _ports = new HashSet<>();
    private final Set<String> _hosts = new HashSet<>();
    
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        String associatePeriod = config.getInitParameter("associatePeriod");
        if (associatePeriod != null)
            _associatePeriod = Long.valueOf(associatePeriod);

        String renew=config.getInitParameter("renewPath");
        if (renew!=null)
            _renewPath=renew;
        
        String hosts = config.getInitParameter("hosts");
        if (hosts!=null)
            for (String h:hosts.split(","))
                _hosts.add(h);
                
        String ports = config.getInitParameter("ports");
        if (ports!=null)
            for (String p:ports.split(","))
                _ports.add(Integer.parseInt(p));

        if (LOG.isDebugEnabled())
            LOG.debug("p={} renew={} hosts={} ports={}",_associatePeriod,_renewPath,_hosts,_ports);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException
    {
        long now=System.nanoTime();
        HttpServletRequest request = (HttpServletRequest)req;
        
        if (Boolean.TRUE==req.getAttribute("org.eclipse.jetty.pushed"))
        {
            if (LOG.isDebugEnabled())
                    LOG.debug("PUSH {}", request.getRequestURI());
            chain.doFilter(req,resp);
            return;
        }

        // Iterating over fields is more efficient than multiple gets
        HttpFields fields = Request.getBaseRequest(req).getHttpFields();
        boolean conditional = false;
        String referrer = null;
        loop: for (int i = 0; i < fields.size(); i++)
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

        String path = URIUtil.addPaths(request.getServletPath(), request.getPathInfo());
        if (path.endsWith(_renewPath))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Renew {}", now);
            _renew=now;
            resp.getOutputStream().print("PUSH CACHE RESET");
            resp.flushBuffer();
            return;
        }

        if (referrer != null)
        {
            HttpURI referrerURI = new HttpURI(referrer);
            String host=referrerURI.getHost();
            int port=referrerURI.getPort();
            if (port<=0)
                port=request.isSecure()?443:80;
                        
            boolean referred_from_here=(_hosts.size()>0 )?_hosts.contains(host):request.getServerName().equals(host);
            referred_from_here&=(_ports.size()>0)?_ports.contains(port):port==request.getServerPort();
            
            if (referred_from_here)
            {
                String referrerPath = referrerURI.getPath();
                if (referrerPath.startsWith(request.getContextPath()))
                {
                    String referrerPathNoContext = referrerPath.substring(request.getContextPath().length());
                    PrimaryResource primaryResource = _cache.get(referrerPathNoContext);
                    if (primaryResource != null)
                    {
                        long primaryTimestamp = primaryResource._timestamp.get();
                        if (primaryTimestamp != 0)
                        {
                            RequestDispatcher dispatcher = request.getServletContext().getRequestDispatcher(path);
                            if (now - primaryTimestamp < TimeUnit.MILLISECONDS.toNanos(_associatePeriod))
                            {
                                if (primaryResource._associated.putIfAbsent(path, dispatcher) == null)
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("Associated {} -> {}", referrerPathNoContext, dispatcher);
                                }
                            }
                            else
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Not associated {} -> {}, outside associate period of {}ms", referrerPathNoContext, dispatcher, _associatePeriod);
                            }
                        }
                    }
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("External referrer {}", referrer);
            }
        }

        // Push some resources?
        PrimaryResource primaryResource = _cache.get(path);
        if (primaryResource == null)
        {
            PrimaryResource t = new PrimaryResource();
            primaryResource = _cache.putIfAbsent(path, t);
            primaryResource = primaryResource == null ? t : primaryResource;
            primaryResource._timestamp.compareAndSet(0, now);
            if (LOG.isDebugEnabled())
                LOG.debug("Cached {}", path);
        }
        else 
        {
            long last=primaryResource._timestamp.get();
            if (last<_renew && primaryResource._timestamp.compareAndSet(last, now))
            {
                primaryResource._associated.clear();
                if (LOG.isDebugEnabled())
                    LOG.debug("Clear associated {}", path);
            }
        }

        // Push associated for non conditional
        if (!conditional && !primaryResource._associated.isEmpty())
        {
            for (RequestDispatcher dispatcher : primaryResource._associated.values())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Pushing {} <- {}", dispatcher, path);
                ((Dispatcher)dispatcher).push(request);
            }
        }

        chain.doFilter(req, resp);
    }

    @Override
    public void destroy()
    {
        _cache.clear();
    }

    @ManagedAttribute("The push cache contents")
    public Map<String, String> getCache()
    {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, PrimaryResource> entry : _cache.entrySet())
        {
            PrimaryResource resource = entry.getValue();
            String value = String.format("size=%d: %s", resource._associated.size(), new TreeSet<>(resource._associated.keySet()));
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static class PrimaryResource
    {
        private final ConcurrentMap<String, RequestDispatcher> _associated = new ConcurrentHashMap<>();
        private final AtomicLong _timestamp = new AtomicLong();
    }
}

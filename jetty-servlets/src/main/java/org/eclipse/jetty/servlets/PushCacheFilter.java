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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
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
 * 
 */
public class PushCacheFilter implements Filter
{
    private static final Logger LOG = Log.getLogger(PushCacheFilter.class);
    private final ConcurrentMap<String, Target> _cache = new ConcurrentHashMap<>();
    
    private long _associateDelay=2000L;
    
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        if (config.getInitParameter("associateDelay")!=null)
            _associateDelay=Long.valueOf(config.getInitParameter("associateDelay"));
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    { 
        Request baseRequest = Request.getBaseRequest(request);
        
        
        // Iterating over fields is more efficient than multiple gets
        HttpFields fields = baseRequest.getHttpFields();
        boolean conditional=false;
        String referer=null;
        loop: for (int i=0;i<fields.size();i++)
        {
            HttpField field=fields.getField(i);
            HttpHeader header=field.getHeader();
            if (header==null)
                continue;
            
            switch (header)
            {
                case IF_MATCH:
                case IF_MODIFIED_SINCE:
                case IF_NONE_MATCH:
                case IF_UNMODIFIED_SINCE:
                    conditional=true;
                    break loop;
                    
                case REFERER:
                    referer=field.getValue();
                    break;
                    
                default:
                    break;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} referer={} conditional={}%n",baseRequest.getMethod(),baseRequest.getRequestURI(),referer,conditional);

        HttpURI uri = null;
        if (!conditional)
        {
            String session = baseRequest.getSession(true).getId();
            String path = URIUtil.addPaths(baseRequest.getServletPath(),baseRequest.getPathInfo());
            
            if (referer!=null)
            {
                uri = new HttpURI(referer);
                if (request.getServerName().equals(uri.getHost()))
                {
                    String from = uri.getPath();
                    if (from.startsWith(baseRequest.getContextPath()))
                    {
                        String from_in_ctx = from.substring(baseRequest.getContextPath().length());
                        
                        Target target = _cache.get(from_in_ctx);
                        if (target!=null)
                        {
                            Long last = target._timestamp.get(session);
                            if (last!=null && (System.currentTimeMillis()-last)<_associateDelay && !target._associated.containsKey(path))
                            {
                                RequestDispatcher dispatcher = baseRequest.getServletContext().getRequestDispatcher(path);
                                if (target._associated.putIfAbsent(path,dispatcher)==null)
                                    LOG.info("ASSOCIATE {}->{}",from_in_ctx,dispatcher);
                            }
                        }
                    }
                }
            }

            // push some resources?
            Target target = _cache.get(path);
            if (target == null)
            {
                Target t=new Target();
                target = _cache.putIfAbsent(path,t);
                target = target==null?t:target;
            }
            target._timestamp.put(session,System.currentTimeMillis());
            if (target._associated.size()>0)
            {
                for (RequestDispatcher dispatcher : target._associated.values())
                {
                    LOG.info("PUSH {}->{}",path,dispatcher);
                    ((Dispatcher)dispatcher).push(request);
                }
            }
        }

        chain.doFilter(request,response);
        
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#destroy()
     */
    @Override
    public void destroy()
    {        
    }

    
    public static class Target
    {
        final ConcurrentMap<String,RequestDispatcher> _associated = new ConcurrentHashMap<>();
        final ConcurrentMap<String,Long> _timestamp = new ConcurrentHashMap<>();
    }
}

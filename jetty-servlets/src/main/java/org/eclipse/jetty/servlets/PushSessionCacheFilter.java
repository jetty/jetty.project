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
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.PushBuilder;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 */
public class PushSessionCacheFilter implements Filter
{
    private static final String TARGET_ATTR="PushCacheFilter.target";
    private static final Logger LOG = Log.getLogger(PushSessionCacheFilter.class);
    private final ConcurrentMap<String, Target> _cache = new ConcurrentHashMap<>();
    
    private long _associateDelay=5000L;
    
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
     */
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        if (config.getInitParameter("associateDelay")!=null)
            _associateDelay=Long.valueOf(config.getInitParameter("associateDelay"));
        
        config.getServletContext().addListener(new ServletRequestListener()
        {
            @Override
            public void requestDestroyed(ServletRequestEvent sre)
            {
                Request request = Request.getBaseRequest(sre.getServletRequest());
                Target target = (Target)request.getAttribute(TARGET_ATTR);
                if (target==null)
                    return;

                // Update conditional data
                Response response = request.getResponse();
                target._etag=response.getHttpFields().get(HttpHeader.ETAG);
                target._lastModified=response.getHttpFields().get(HttpHeader.LAST_MODIFIED);
                
                // Does this request have a referer?
                String referer = request.getHttpFields().get(HttpHeader.REFERER);
                if (referer!=null)
                {
                    // Is the referer from this contexts?
                    HttpURI uri = new HttpURI(referer);
                    String path = uri.getPath();
                    if (request.getServerName().equals(uri.getHost()) && path.startsWith(request.getContextPath()))
                    {
                        String path_in_ctx = path.substring(request.getContextPath().length());
                        Target referer_target = _cache.get(path_in_ctx);
                        if (referer_target!=null)
                        {
                            String sessionId = request.getSession(true).getId();
                            Long last = referer_target._timestamp.get(sessionId);
                            if (last!=null && (System.currentTimeMillis()-last)<_associateDelay && !referer_target._associated.containsKey(path))
                            {
                                if (referer_target._associated.putIfAbsent(path,target)==null)
                                    LOG.info("ASSOCIATE {}->{}",path_in_ctx,target._path);
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
        String referer=fields.get(HttpHeader.REFERER);
        
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} referer={}%n",baseRequest.getMethod(),baseRequest.getRequestURI(),referer);


        HttpSession session = baseRequest.getSession(true);
        String sessionId = session.getId();
        String path = URIUtil.addPaths(baseRequest.getServletPath(),baseRequest.getPathInfo());

        // find the target for this resource
        Target target = _cache.get(path);
        if (target == null)
        {
            Target t=new Target(path);
            target = _cache.putIfAbsent(path,t);
            target = target==null?t:target;
        }
        target._timestamp.put(sessionId,System.currentTimeMillis());
        request.setAttribute(TARGET_ATTR,target);
        
        // push any associated resources
        if (target._associated.size()>0)
        {
            PushBuilder builder = baseRequest.getPushBuilder();
            if (!session.isNew())
                builder.setConditional(true);
            if (builder!=null)
            {
                for (Target associated : target._associated.values())
                {
                    LOG.info("PUSH {}->{}",path,associated._path);
                    builder.push(associated._path,associated._etag,associated._lastModified);
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
        final String _path;
        final ConcurrentMap<String,Target> _associated = new ConcurrentHashMap<>();
        final ConcurrentMap<String,Long> _timestamp = new ConcurrentHashMap<>();
        volatile String _etag;
        volatile String _lastModified;
        
        public Target(String path)
        {
            _path=path;
        }
        
    }
}

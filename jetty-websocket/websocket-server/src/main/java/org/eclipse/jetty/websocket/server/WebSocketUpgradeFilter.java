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

package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.pathmap.PathMappings;
import org.eclipse.jetty.websocket.server.pathmap.PathMappings.MappedResource;
import org.eclipse.jetty.websocket.server.pathmap.PathSpec;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests and perform path mappings to {@link WebSocketCreator} objects.
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter extends ContainerLifeCycle implements Filter, MappedWebSocketCreator, Dumpable
{
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeFilter.class);

    public static WebSocketUpgradeFilter configureContext(ServletContextHandler context)
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

        WebSocketUpgradeFilter filter = new WebSocketUpgradeFilter(policy);
        FilterHolder fholder = new FilterHolder(filter);
        fholder.setName("Jetty_WebSocketUpgradeFilter");
        fholder.setDisplayName("WebSocket Upgrade Filter");
        String pathSpec = "/*";
        context.addFilter(fholder,pathSpec,EnumSet.of(DispatcherType.REQUEST));

        if (LOG.isDebugEnabled())
            LOG.debug("Adding {} mapped to {} to {}",filter,pathSpec,context);

        // Store reference to the WebSocketUpgradeFilter
        context.setAttribute(WebSocketUpgradeFilter.class.getName(),filter);

        return filter;
    }

    private final WebSocketServerFactory factory;
    private final PathMappings<WebSocketCreator> pathmap = new PathMappings<>();

    public WebSocketUpgradeFilter(WebSocketPolicy policy)
    {
        this(policy, new MappedByteBufferPool());
    }
    
    public WebSocketUpgradeFilter(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        factory = new WebSocketServerFactory(policy,bufferPool);
        addBean(factory,true);
    }

    @Override
    public void addMapping(PathSpec spec, WebSocketCreator creator)
    {
        pathmap.put(spec,creator);
    }

    @Override
    public void destroy()
    {
        factory.cleanup();
        pathmap.reset();
        super.destroy();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (factory == null)
        {
            // no factory, cannot operate
            LOG.debug("WebSocketUpgradeFilter is not operational - no WebSocketServletFactory configured");
            chain.doFilter(request,response);
            return;
        }

        if ((request instanceof HttpServletRequest) && (response instanceof HttpServletResponse))
        {
            HttpServletRequest httpreq = (HttpServletRequest)request;
            HttpServletResponse httpresp = (HttpServletResponse)response;

            // Since this is a filter, we need to be smart about determining the target path
            String contextPath = httpreq.getContextPath();
            String target = httpreq.getRequestURI();
            if (target.startsWith(contextPath))
            {
                target = target.substring(contextPath.length());
            }

            if (factory.isUpgradeRequest(httpreq,httpresp))
            {
                LOG.debug("target = [{}]",target);

                MappedResource<WebSocketCreator> resource = pathmap.getMatch(target);
                if (resource == null)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("WebSocket Upgrade on {} has no associated endpoint",target);
                        LOG.debug("PathMappings: {}",pathmap.dump());
                    }
                    // no match.
                    chain.doFilter(request,response);
                    return;
                }
                LOG.debug("WebSocket Upgrade detected on {} for endpoint {}",target,resource);

                WebSocketCreator creator = resource.getResource();

                // Store PathSpec resource mapping as request attribute
                httpreq.setAttribute(PathSpec.class.getName(),resource.getPathSpec());

                // We have an upgrade request
                if (factory.acceptWebSocket(creator,httpreq,httpresp))
                {
                    // We have a socket instance created
                    return;
                }

                // If we reach this point, it means we had an incoming request to upgrade
                // but it was either not a proper websocket upgrade, or it was possibly rejected
                // due to incoming request constraints (controlled by WebSocketCreator)
                if (response.isCommitted())
                {
                    // not much we can do at this point.
                    return;
                }
            }
        }

        // not an Upgrade request
        chain.doFilter(request,response);
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(indent).append(" +- pathmap=").append(pathmap.toString()).append("\n");
        pathmap.dump(out,indent + "   ");
    }

    public WebSocketServerFactory getFactory()
    {
        return factory;
    }

    @ManagedAttribute(value = "mappings", readonly = true)
    @Override
    public PathMappings<WebSocketCreator> getMappings()
    {
        return pathmap;
    }

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        try
        {
            WebSocketPolicy policy = factory.getPolicy();

            String max = config.getInitParameter("maxIdleTime");
            if (max != null)
            {
                policy.setIdleTimeout(Long.parseLong(max));
            }

            max = config.getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                policy.setMaxTextMessageSize(Integer.parseInt(max));
            }

            max = config.getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                policy.setMaxBinaryMessageSize(Integer.parseInt(max));
            }

            max = config.getInitParameter("inputBufferSize");
            if (max != null)
            {
                policy.setInputBufferSize(Integer.parseInt(max));
            }

            factory.start();
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[factory=%s,pathmap=%s]",this.getClass().getSimpleName(),factory,pathmap);
    }
}

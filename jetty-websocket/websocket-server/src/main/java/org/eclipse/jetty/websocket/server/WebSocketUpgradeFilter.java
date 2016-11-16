//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests and perform path mappings to {@link WebSocketCreator} objects.
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter extends ContainerLifeCycle implements Filter, MappedWebSocketCreator, Dumpable
{
    public static final String CONTEXT_ATTRIBUTE_KEY = "contextAttributeKey";
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeFilter.class);
    
    public static WebSocketUpgradeFilter configureContext(ServletContextHandler context) throws ServletException
    {
        // Prevent double configure
        WebSocketUpgradeFilter filter = (WebSocketUpgradeFilter) context.getAttribute(WebSocketUpgradeFilter.class.getName());
        if (filter != null)
        {
            return filter;
        }
        
        // Dynamically add filter
        filter = new WebSocketUpgradeFilter();
        filter.setToAttribute(context, WebSocketUpgradeFilter.class.getName());
        
        String name = "Jetty_WebSocketUpgradeFilter";
        String pathSpec = "/*";
        EnumSet<DispatcherType> dispatcherTypes = EnumSet.of(DispatcherType.REQUEST);
        
        FilterHolder fholder = new FilterHolder(filter);
        fholder.setName(name);
        fholder.setAsyncSupported(true);
        fholder.setInitParameter(CONTEXT_ATTRIBUTE_KEY, WebSocketUpgradeFilter.class.getName());
        context.addFilter(fholder, pathSpec, dispatcherTypes);
        
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Adding [{}] {} mapped to {} to {}", name, filter, pathSpec, context);
        }
        
        return filter;
    }
    
    /**
     * @deprecated use {@link #configureContext(ServletContextHandler)} instead
     */
    @Deprecated
    public static WebSocketUpgradeFilter configureContext(ServletContext context) throws ServletException
    {
        ContextHandler handler = ContextHandler.getContextHandler(context);
        
        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, WebSocket support unavailable");
        }
        
        if (!(handler instanceof ServletContextHandler))
        {
            throw new ServletException("Not running in Jetty ServletContextHandler, WebSocket support via " + WebSocketUpgradeFilter.class.getName() + " unavailable");
        }
        
        return configureContext((ServletContextHandler) handler);
    }
    
    public static final String CREATOR_KEY = "org.eclipse.jetty.websocket.server.creator";
    public static final String FACTORY_KEY = "org.eclipse.jetty.websocket.server.factory";
    
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private WebSocketServerFactory factory;
    private MappedWebSocketCreator mappedWebSocketCreator;
    private String fname;
    private boolean alreadySetToAttribute = false;
    
    public WebSocketUpgradeFilter()
    {
        this(WebSocketPolicy.newServerPolicy(), new MappedByteBufferPool());
    }
    
    public WebSocketUpgradeFilter(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this.policy = policy;
        this.bufferPool = bufferPool;
    }
    
    @Override
    public void addMapping(PathSpec spec, WebSocketCreator creator)
    {
        mappedWebSocketCreator.addMapping(spec, creator);
    }
    
    /**
     * @deprecated use new {@link #addMapping(org.eclipse.jetty.http.pathmap.PathSpec, WebSocketCreator)} instead
     */
    @Deprecated
    public void addMapping(org.eclipse.jetty.websocket.server.pathmap.PathSpec spec, WebSocketCreator creator)
    {
        if (spec instanceof org.eclipse.jetty.websocket.server.pathmap.ServletPathSpec)
        {
            addMapping(new ServletPathSpec(spec.getSpec()), creator);
        }
        else if (spec instanceof org.eclipse.jetty.websocket.server.pathmap.RegexPathSpec)
        {
            addMapping(new RegexPathSpec(spec.getSpec()), creator);
        }
        else
        {
            throw new RuntimeException("Unsupported (Deprecated) PathSpec implementation: " + spec.getClass().getName());
        }
    }

    @Override
    public void destroy()
    {
        factory.cleanup();
        super.destroy();
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (factory == null)
        {
            // no factory, cannot operate
            LOG.debug("WebSocketUpgradeFilter is not operational - no WebSocketServletFactory configured");
            chain.doFilter(request, response);
            return;
        }
        
        try
        {
            HttpServletRequest httpreq = (HttpServletRequest) request;
            HttpServletResponse httpresp = (HttpServletResponse) response;
            
            if (!factory.isUpgradeRequest(httpreq, httpresp))
            {
                // Not an upgrade request, skip it
                chain.doFilter(request, response);
                return;
            }
            
            // Since this is a filter, we need to be smart about determining the target path
            String contextPath = httpreq.getContextPath();
            String target = httpreq.getRequestURI();
            if (target.startsWith(contextPath))
            {
                target = target.substring(contextPath.length());
            }
            
            MappedResource<WebSocketCreator> resource = mappedWebSocketCreator.getMappings().getMatch(target);
            if (resource == null)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("WebSocket Upgrade on {} has no associated endpoint", target);
                    LOG.debug("PathMappings: {}", mappedWebSocketCreator.getMappings().dump());
                }
                // no match.
                chain.doFilter(request, response);
                return;
            }
            
            if (LOG.isDebugEnabled())
            {
                LOG.debug("WebSocket Upgrade detected on {} for endpoint {}", target, resource);
            }
            
            WebSocketCreator creator = resource.getResource();
            
            // Store PathSpec resource mapping as request attribute
            httpreq.setAttribute(PathSpec.class.getName(), resource.getPathSpec());
            
            // We have an upgrade request
            if (factory.acceptWebSocket(creator, httpreq, httpresp))
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
        catch (ClassCastException e)
        {
            // We are in some kind of funky non-http environment.
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Not a HttpServletRequest, skipping WebSocketUpgradeFilter");
            }
        }
        
        // not an Upgrade request
        chain.doFilter(request, response);
    }
    
    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
    
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(indent).append(" +- pathmap=").append(mappedWebSocketCreator.toString()).append("\n");
        mappedWebSocketCreator.getMappings().dump(out, indent + "   ");
    }
    
    public WebSocketServerFactory getFactory()
    {
        return factory;
    }
    
    @ManagedAttribute(value = "mappings", readonly = true)
    @Override
    public PathMappings<WebSocketCreator> getMappings()
    {
        return mappedWebSocketCreator.getMappings();
    }
    
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        fname = config.getFilterName();
        
        try
        {
            mappedWebSocketCreator = (MappedWebSocketCreator) config.getServletContext().getAttribute(CREATOR_KEY);
            if (mappedWebSocketCreator == null)
            {
                mappedWebSocketCreator = new DefaultMappedWebSocketCreator();
            }
            
            factory = (WebSocketServerFactory) config.getServletContext().getAttribute(FACTORY_KEY);
            if (factory == null)
            {
                factory = new WebSocketServerFactory(policy, bufferPool);
            }
            addBean(factory, true);
            
            // TODO: Policy isn't from attributes
            
            String max = config.getInitParameter("maxIdleTime");
            if (max != null)
            {
                factory.getPolicy().setIdleTimeout(Long.parseLong(max));
            }
            
            max = config.getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                factory.getPolicy().setMaxTextMessageSize(Integer.parseInt(max));
            }
            
            max = config.getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                factory.getPolicy().setMaxBinaryMessageSize(Integer.parseInt(max));
            }
            
            max = config.getInitParameter("inputBufferSize");
            if (max != null)
            {
                factory.getPolicy().setInputBufferSize(Integer.parseInt(max));
            }
            
            String key = config.getInitParameter(CONTEXT_ATTRIBUTE_KEY);
            if (key == null)
            {
                // assume default
                key = WebSocketUpgradeFilter.class.getName();
            }
            
            setToAttribute(ctx, key);
            
            factory.start();
        }
        catch (Exception x)
        {
            throw new ServletException(x);
        }
    }
    
    private void setToAttribute(ServletContextHandler context, String key) throws ServletException
    {
        if (alreadySetToAttribute)
        {
            return;
        }
        
        if (context.getAttribute(key) != null)
        {
            throw new ServletException(WebSocketUpgradeFilter.class.getName() +
                    " is defined twice for the same context attribute key '" + key
                    + "'.  Make sure you have different init-param '" +
                    CONTEXT_ATTRIBUTE_KEY + "' values set");
        }
        context.setAttribute(key, this);
        
        alreadySetToAttribute = true;
    }
    
    public void setToAttribute(ServletContext context, String key) throws ServletException
    {
        if (alreadySetToAttribute)
        {
            return;
        }
        
        if (context.getAttribute(key) != null)
        {
            throw new ServletException(WebSocketUpgradeFilter.class.getName() +
                    " is defined twice for the same context attribute key '" + key
                    + "'.  Make sure you have different init-param '" +
                    CONTEXT_ATTRIBUTE_KEY + "' values set");
        }
        context.setAttribute(key, this);
        
        alreadySetToAttribute = true;
    }
    
    @Override
    public String toString()
    {
        return String.format("%s[factory=%s,creator=%s]", this.getClass().getSimpleName(), factory, mappedWebSocketCreator);
    }
}

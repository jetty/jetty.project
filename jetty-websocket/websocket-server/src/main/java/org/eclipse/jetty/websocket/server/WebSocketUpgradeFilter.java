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
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests and perform path mappings to {@link WebSocketCreator} objects.
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter implements Filter, MappedWebSocketCreator, Dumpable
{
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeFilter.class);
    public static final String CONTEXT_ATTRIBUTE_KEY = "contextAttributeKey";
    public static final String CONFIG_ATTRIBUTE_KEY = "configAttributeKey";

    public static WebSocketUpgradeFilter configureContext(ServletContextHandler context) throws ServletException
    {
        // Prevent double configure
        WebSocketUpgradeFilter filter = (WebSocketUpgradeFilter) context.getAttribute(WebSocketUpgradeFilter.class.getName());
        if (filter != null)
        {
            return filter;
        }
        
        // Dynamically add filter
        NativeWebSocketConfiguration configuration = NativeWebSocketServletContainerInitializer.getDefaultFrom(context.getServletContext());
        filter = new WebSocketUpgradeFilter(configuration);
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
    
    private NativeWebSocketConfiguration configuration;
    private boolean localConfiguration = false;
    private boolean alreadySetToAttribute = false;
    
    public WebSocketUpgradeFilter()
    {
        // do nothing
    }
    
    public WebSocketUpgradeFilter(WebSocketServerFactory factory)
    {
        this(new NativeWebSocketConfiguration(factory));
    }
    
    public WebSocketUpgradeFilter(NativeWebSocketConfiguration configuration)
    {
        this.configuration = configuration;
    }
    
    @Override
    public void addMapping(PathSpec spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }
    
    /**
     * @deprecated use new {@link #addMapping(org.eclipse.jetty.http.pathmap.PathSpec, WebSocketCreator)} instead
     */
    @Deprecated
    @Override
    public void addMapping(org.eclipse.jetty.websocket.server.pathmap.PathSpec spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }

    @Override
    public void destroy()
    {
        try
        {
            alreadySetToAttribute = false;
            if(localConfiguration)
            {
                configuration.stop();
            }
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        if (configuration == null)
        {
            // no configuration, cannot operate
            LOG.debug("WebSocketUpgradeFilter is not operational - missing " + NativeWebSocketConfiguration.class.getName());
            chain.doFilter(request, response);
            return;
        }
        
        WebSocketServletFactory factory = configuration.getFactory();
        
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
            
            MappedResource<WebSocketCreator> resource = configuration.getMatch(target);
            if (resource == null)
            {
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
        out.append(indent).append(" +- configuration=").append(configuration.toString()).append("\n");
        configuration.dump(out, indent);
    }
    
    public WebSocketServletFactory getFactory()
    {
        return configuration.getFactory();
    }
    
    @ManagedAttribute(value = "configuration", readonly = true)
    public NativeWebSocketConfiguration getConfiguration()
    {
        if (configuration == null)
        {
            throw new IllegalStateException(this.getClass().getName() + " not initialized yet");
        }
        return configuration;
    }
    
    @Override
    public MappedResource<WebSocketCreator> getMapping(String target)
    {
        return getConfiguration().getMatch(target);
    }
    
    @Override
    public void init(FilterConfig config) throws ServletException
    {
        try
        {
            String configurationKey = config.getInitParameter(CONFIG_ATTRIBUTE_KEY);
            if (configurationKey == null)
            {
                configurationKey = NativeWebSocketConfiguration.class.getName();
            }
            
            if (configuration == null)
            {
                this.configuration = (NativeWebSocketConfiguration) config.getServletContext().getAttribute(configurationKey);
                if (this.configuration == null)
                {
                    // The NativeWebSocketConfiguration should have arrived from the NativeWebSocketServletContainerInitializer
                    throw new ServletException("Unable to find required instance of " +
                            NativeWebSocketConfiguration.class.getName() + " at ServletContext attribute '" + configurationKey + "'");
                }
            }
            else
            {
                // We have a NativeWebSocketConfiguration already present, make sure it exists on the ServletContext
                if (config.getServletContext().getAttribute(configurationKey) == null)
                {
                    config.getServletContext().setAttribute(configurationKey, this.configuration);
                }
            }
            
            if(!this.configuration.isRunning())
            {
                localConfiguration = true;
                this.configuration.start();
            }
            
            String max = config.getInitParameter("maxIdleTime");
            if (max != null)
            {
                getFactory().getPolicy().setIdleTimeout(Long.parseLong(max));
            }
            
            max = config.getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                getFactory().getPolicy().setMaxTextMessageSize(Integer.parseInt(max));
            }
            
            max = config.getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                getFactory().getPolicy().setMaxBinaryMessageSize(Integer.parseInt(max));
            }
            
            max = config.getInitParameter("inputBufferSize");
            if (max != null)
            {
                getFactory().getPolicy().setInputBufferSize(Integer.parseInt(max));
            }
            
            String key = config.getInitParameter(CONTEXT_ATTRIBUTE_KEY);
            if (key == null)
            {
                // assume default
                key = WebSocketUpgradeFilter.class.getName();
            }
            
            // Set instance of this filter to context attribute
            setToAttribute(config.getServletContext(), key);
        }
        catch (ServletException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new ServletException(t);
        }
    }
    
    private void setToAttribute(ServletContextHandler context, String key) throws ServletException
    {
        setToAttribute(context.getServletContext(), key);
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
        return String.format("%s[configuration=%s]", this.getClass().getSimpleName(), configuration);
    }
}

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
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
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
    public static final String ATTR_KEY = WebSocketUpgradeFilter.class.getName();

    /**
     * Configure the default WebSocketUpgradeFilter.
     *
     * <p>
     * This will return the default {@link WebSocketUpgradeFilter} on the
     * provided {@link ServletContextHandler}, creating the filter if necessary.
     * </p>
     * <p>
     * The default {@link WebSocketUpgradeFilter} is also available via
     * the {@link ServletContext} attribute named {@code org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter}
     * </p>
     *
     * @param context the {@link ServletContextHandler} to use
     * @return the configured default {@link WebSocketUpgradeFilter} instance
     * @throws ServletException if the filer cannot be configured
     */
    public static WebSocketUpgradeFilter configure(ServletContextHandler context) throws ServletException
    {
        // Prevent double configure
        WebSocketUpgradeFilter filter = (WebSocketUpgradeFilter)context.getAttribute(ATTR_KEY);
        if (filter == null)
        {
            // Dynamically add filter
            NativeWebSocketConfiguration configuration = NativeWebSocketServletContainerInitializer.initialize(context);
            filter = new WebSocketUpgradeFilter(configuration);
            filter.setToAttribute(context, ATTR_KEY);

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
        }

        return filter;
    }

    /**
     * @param context the {@link ServletContextHandler} to use
     * @return a configured {@link WebSocketUpgradeFilter} instance
     * @throws ServletException if the filer cannot be configured
     * @deprecated use {@link #configure(ServletContextHandler)} instead
     */
    @Deprecated
    public static WebSocketUpgradeFilter configureContext(ServletContextHandler context) throws ServletException
    {
        return configure(context);
    }

    /**
     * @param context the ServletContext to use
     * @return a configured {@link WebSocketUpgradeFilter} instance
     * @throws ServletException if the filer cannot be configured
     * @deprecated use {@link #configureContext(ServletContextHandler)} instead
     */
    @Deprecated
    public static WebSocketUpgradeFilter configureContext(ServletContext context) throws ServletException
    {
        ServletContextHandler handler = ServletContextHandler.getServletContextHandler(context);
        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, WebSocket support unavailable");
        }
        return configure(handler);
    }

    private NativeWebSocketConfiguration configuration;
    private String instanceKey;
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
    public void addMapping(String spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }

    @Override
    public boolean removeMapping(String spec)
    {
        return configuration.removeMapping(spec);
    }

    @Override
    public void destroy()
    {
        try
        {
            alreadySetToAttribute = false;
            if (localConfiguration)
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
            if (LOG.isDebugEnabled())
                LOG.debug("WebSocketUpgradeFilter is not operational - missing " + NativeWebSocketConfiguration.class.getName());
            chain.doFilter(request, response);
            return;
        }

        WebSocketServletFactory factory = configuration.getFactory();

        if (factory == null)
        {
            // no factory, cannot operate
            if (LOG.isDebugEnabled())
                LOG.debug("WebSocketUpgradeFilter is not operational - no WebSocketServletFactory configured");
            chain.doFilter(request, response);
            return;
        }

        try
        {
            HttpServletRequest httpreq = (HttpServletRequest)request;
            HttpServletResponse httpresp = (HttpServletResponse)response;

            if (!factory.isUpgradeRequest(httpreq, httpresp))
            {
                // Not an upgrade request, skip it
                chain.doFilter(request, response);
                return;
            }

            // Since this is a filter, we need to be smart about determining the target path.
            // We should rely on the Container for stripping path parameters and its ilk before
            // attempting to match a specific mapped websocket creator.
            String target = httpreq.getServletPath();
            if (httpreq.getPathInfo() != null)
            {
                target = target + httpreq.getPathInfo();
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
                LOG.debug("Not an HttpServletRequest, skipping WebSocketUpgradeFilter");
            }
        }

        // not an Upgrade request
        chain.doFilter(request, response);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, configuration);
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
    public WebSocketCreator getMapping(String target)
    {
        return getConfiguration().getMapping(target);
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
                this.configuration = (NativeWebSocketConfiguration)config.getServletContext().getAttribute(configurationKey);
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

            if (!this.configuration.isRunning())
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

            instanceKey = config.getInitParameter(CONTEXT_ATTRIBUTE_KEY);
            if (instanceKey == null)
            {
                // assume default
                instanceKey = WebSocketUpgradeFilter.class.getName();
            }

            // Set instance of this filter to context attribute
            setToAttribute(config.getServletContext(), instanceKey);
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
                " is defined twice for the same context attribute key '" + key +
                "'.  Make sure you have different init-param '" +
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

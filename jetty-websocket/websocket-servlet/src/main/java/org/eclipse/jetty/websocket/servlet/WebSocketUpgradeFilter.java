//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.servlet;

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
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.HandshakerFactory;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests and perform path mappings to {@link WebSocketServletNegotiator} objects.
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter implements Filter, Dumpable
{
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeFilter.class);
    public static final String CONTEXT_ATTRIBUTE_KEY = "contextAttributeKey";
    public static final String CONFIG_ATTRIBUTE_KEY = "configAttributeKey";

    private ServletContextWebSocketContainer servletContextWebSocketContainer;

    /**
     * @param context the {@link ServletContextHandler} to use
     * @return a configured {@link WebSocketUpgradeFilter} instance
     * @throws ServletException if the filer cannot be configured
     */
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
     * @param context the ServletContext to use
     * @return a configured {@link WebSocketUpgradeFilter} instance
     * @throws ServletException if the filer cannot be configured
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
    private String instanceKey;
    private boolean localConfiguration = false;
    private boolean alreadySetToAttribute = false;

    @SuppressWarnings("unused")
    public WebSocketUpgradeFilter()
    {
        // do nothing
    }

    @SuppressWarnings("unused")
    public WebSocketUpgradeFilter(WebSocketServletFactory factory)
    {
        this(new NativeWebSocketConfiguration(factory));
    }

    public WebSocketUpgradeFilter(NativeWebSocketConfiguration configuration)
    {
        this.configuration = configuration;
    }

    /**
     * Add a Negotiator Mapping
     *
     * @param pathSpec the path spec
     * @param negotiator the negotiator
     * @see NativeWebSocketConfiguration#addMapping(PathSpec, WebSocketServletNegotiator)
     * @deprecated use {@link #getConfiguration()}.{@link NativeWebSocketConfiguration#addMapping(PathSpec, WebSocketServletNegotiator)} instead.
     */
    @Deprecated
    public void addMapping(PathSpec pathSpec, WebSocketServletNegotiator negotiator)
    {
        configuration.addMapping(pathSpec, negotiator);
    }

    /**
     * Add a mapping
     *
     * @param spec the path spec
     * @param creator the creator (to be wrapped by {@link WebSocketServletNegotiator})
     * @see NativeWebSocketConfiguration#addMapping(PathSpec, WebSocketCreator)
     * @deprecated use {@link #getConfiguration()}.{@link NativeWebSocketConfiguration#addMapping(PathSpec, WebSocketCreator)} instead.
     */
    @Deprecated
    public void addMapping(PathSpec spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }

    /**
     * Add a mapping
     *
     * @param spec the path spec
     * @param creator the creator (to be wrapped by {@link WebSocketServletNegotiator})
     * @see NativeWebSocketConfiguration#addMapping(String, WebSocketCreator)
     * @deprecated use {@link #getConfiguration()}.{@link NativeWebSocketConfiguration#addMapping(String, WebSocketCreator)} instead.
     */
    @Deprecated
    public void addMapping(String spec, WebSocketCreator creator)
    {
        configuration.addMapping(spec, creator);
    }

    /**
     * Remove a mapping
     *
     * @param spec the path spec
     * @see NativeWebSocketConfiguration#removeMapping(String)
     * @deprecated use {@link #getConfiguration()}.{@link NativeWebSocketConfiguration#removeMapping(String)} instead.
     */
    @Deprecated
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

            Handshaker handshaker = HandshakerFactory.getHandshaker(httpreq);

            if (handshaker == null)
            {
                // Not a request that can be upgraded, skip it
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

            MappedResource<WebSocketServletNegotiator> resource = configuration.getMatch(target);
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

            WebSocketServletNegotiator negotiator = resource.getResource();

            // Store PathSpec resource mapping as request attribute, for WebSocketCreator
            // implementors to use later if they wish
            httpreq.setAttribute(PathSpec.class.getName(), resource.getPathSpec());

            // We have an upgrade request
            if (handshaker.upgradeRequest(negotiator, httpreq, httpresp))
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

        // This means we got a request that looked like an upgrade request, but
        // didn't actually upgrade, or produce an error, so process normally in the servlet chain.
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

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
import java.time.Duration;
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
import org.eclipse.jetty.websocket.core.server.internal.RFC6455Handshaker;
import org.eclipse.jetty.websocket.servlet.internal.WebSocketServletFactoryImpl;
import org.eclipse.jetty.websocket.servlet.internal.WebSocketServletNegotiator;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests and perform path mappings to {@link WebSocketServletNegotiator} objects.
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter implements Filter, Dumpable
{
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeFilter.class);
    public static final String CONTEXT_ATTRIBUTE_KEY = "contextAttributeKey";

    private final RFC6455Handshaker handshaker = new RFC6455Handshaker();

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

    private final WebSocketServletFactoryImpl factory;
    private String instanceKey;
    private boolean alreadySetToAttribute = false;

    @SuppressWarnings("unused")
    public WebSocketUpgradeFilter()
    {
        this(new WebSocketServletFactoryImpl());
    }

    public WebSocketUpgradeFilter(WebSocketServletFactoryImpl factory)
    {
        this.factory = factory;
    }

    @Override
    public void destroy()
    {
        try
        {
            alreadySetToAttribute = false;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        try
        {
            HttpServletRequest httpreq = (HttpServletRequest) request;
            HttpServletResponse httpresp = (HttpServletResponse) response;

            // Since this is a filter, we need to be smart about determining the target path.
            // We should rely on the Container for stripping path parameters and its ilk before
            // attempting to match a specific mapped websocket creator.
            String target = httpreq.getServletPath();
            if (httpreq.getPathInfo() != null)
            {
                target = target + httpreq.getPathInfo();
            }

            MappedResource<WebSocketServletNegotiator> resource = factory.getMatchedResource(target);
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
        out.append(indent).append(" +- factory=").append(factory.toString()).append("\n");
        factory.dump(out, indent);
    }

    @ManagedAttribute(value = "factory", readonly = true)
    public WebSocketServletFactory getFactory()
    {
        return factory;
    }

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        try
        {
            final ServletContext context = config.getServletContext();

            factory.setContextClassLoader(context.getClassLoader());

            String max = config.getInitParameter("maxIdleTime");
            if (max != null)
            {
                getFactory().setDefaultIdleTimeout(Duration.ofMillis(Long.parseLong(max)));
            }

            max = config.getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                getFactory().setDefaultMaxTextMessageSize(Integer.parseInt(max));
            }

            max = config.getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                getFactory().setDefaultMaxBinaryMessageSize(Integer.parseInt(max));
            }

            max = config.getInitParameter("inputBufferSize");
            if (max != null)
            {
                getFactory().setDefaultInputBufferSize(Integer.parseInt(max));
            }

            max = config.getInitParameter("outputBufferSize");
            if (max != null)
            {
                getFactory().setDefaultOutputBufferSize(Integer.parseInt(max));
            }

            max = config.getInitParameter("maxAllowedFrameSize");
            if (max != null)
            {
                getFactory().setDefaultMaxAllowedFrameSize(Long.parseLong(max));
            }

            String autoFragment = config.getInitParameter("autoFragment");
            if (autoFragment != null)
            {
                getFactory().setAutoFragment(Boolean.parseBoolean(autoFragment));
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
        return String.format("%s[factory=%s]", this.getClass().getSimpleName(), factory);
    }
}

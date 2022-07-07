//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.servlet;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.FilterMapping;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests.
 * <p>
 * The configuration applied to this filter via init params will be used as the the default
 * configuration of any websocket upgraded by this filter, prior to the configuration of the
 * websocket applied by the {@link WebSocketMappings}.
 * </p>
 * <p>
 * <b>Configuration / Init-Parameters:</b>
 * </p>
 * <dl>
 * <dt>idleTimeout</dt>
 * <dd>set the time in ms that a websocket may be idle before closing<br>
 * <dt>maxTextMessageSize</dt>
 * <dd>set the size in UTF-8 bytes that a websocket may be accept as a Text Message before closing<br>
 * <dt>maxBinaryMessageSize</dt>
 * <dd>set the size in bytes that a websocket may be accept as a Binary Message before closing<br>
 * <dt>inputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to read raw bytes from the network layer<br>
 * <dt>outputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to write bytes to the network layer<br>
 * <dt>maxFrameSize</dt>
 * <dd>The maximum frame size sent or received.<br>
 * <dt>autoFragment</dt>
 * <dd>If true, frames are automatically fragmented to respect the maximum frame size.<br>
 * </dl>
 */
@ManagedObject("WebSocket Upgrade Filter")
public class WebSocketUpgradeFilter implements Filter, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketUpgradeFilter.class);
    private static final AutoLock LOCK = new AutoLock();

    /**
     * Return the default {@link WebSocketUpgradeFilter} if present on the {@link ServletContext}.
     *
     * @param servletContext the {@link ServletContext} to use.
     * @return the configured default {@link WebSocketUpgradeFilter} instance.
     */
    public static FilterHolder getFilter(ServletContext servletContext)
    {
        ContextHandler contextHandler = Objects.requireNonNull(ServletContextHandler.getServletContextHandler(servletContext));
        ServletHandler servletHandler = contextHandler.getDescendant(ServletHandler.class);
        return servletHandler.getFilter(WebSocketUpgradeFilter.class.getName());
    }

    /**
     * Ensure a {@link WebSocketUpgradeFilter} is available on the provided {@link ServletContext},
     * a new filter will added if one does not already exist.
     * <p>
     * The default {@link WebSocketUpgradeFilter} is also available via
     * the {@link ServletContext} attribute named {@code org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter}
     * </p>
     *
     * @param servletContext the {@link ServletContext} to use.
     * @return the configured default {@link WebSocketUpgradeFilter} instance.
     */
    public static FilterHolder ensureFilter(ServletContext servletContext)
    {
        // Lock in case two concurrent requests are initializing the filter lazily.
        try (AutoLock l = LOCK.lock())
        {
            FilterHolder existingFilter = WebSocketUpgradeFilter.getFilter(servletContext);
            if (existingFilter != null)
                return existingFilter;

            ContextHandler contextHandler = Objects.requireNonNull(ServletContextHandler.getServletContextHandler(servletContext));
            ServletHandler servletHandler = contextHandler.getDescendant(ServletHandler.class);

            final String pathSpec = "/*";
            FilterHolder holder = new FilterHolder(new WebSocketUpgradeFilter());
            holder.setName(WebSocketUpgradeFilter.class.getName());
            holder.setAsyncSupported(true);

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));

            // Add the default WebSocketUpgradeFilter as the first filter in the list.
            servletHandler.prependFilter(holder);
            servletHandler.prependFilterMapping(mapping);

            // If we create the filter we must also make sure it is removed if the context is stopped.
            contextHandler.addEventListener(new LifeCycle.Listener()
            {
                @Override
                public void lifeCycleStopping(LifeCycle event)
                {
                    servletHandler.removeFilterHolder(holder);
                    servletHandler.removeFilterMapping(mapping);
                    contextHandler.removeEventListener(this);
                }

                @Override
                public String toString()
                {
                    return String.format("%sCleanupListener", WebSocketUpgradeFilter.class.getSimpleName());
                }
            });

            if (LOG.isDebugEnabled())
                LOG.debug("Adding {} mapped to {} in {}", holder, pathSpec, servletContext);
            return holder;
        }
    }

    private final Configuration.ConfigurationCustomizer defaultCustomizer = new Configuration.ConfigurationCustomizer();
    private WebSocketMappings mappings;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        ServletContextRequest baseRequest = ServletContextRequest.getBaseRequest(request);
        if (baseRequest == null)
            throw new IllegalStateException("Base Request not available");

        // provide a null default customizer the customizer will be on the negotiator in the mapping
        try (Blocker.Callback callback = Blocker.callback())
        {
            if (mappings.upgrade(baseRequest, baseRequest.getResponse(), callback, defaultCustomizer))
            {
                callback.block();
                return;
            }
        }

        // If we reach this point, it means we had an incoming request to upgrade
        // but it was either not a proper websocket upgrade, or it was possibly rejected
        // due to incoming request constraints (controlled by WebSocketCreator)
        if (response.isCommitted())
            return;

        // Handle normally
        chain.doFilter(request, response);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, defaultCustomizer, mappings);
    }

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        ServletContext servletContext = config.getServletContext();
        ServletContextHandler contextHandler = ServletContextHandler.getServletContextHandler(servletContext);
        mappings = WebSocketMappings.ensureMappings(contextHandler);

        String max = config.getInitParameter("idleTimeout");
        if (max == null)
        {
            max = config.getInitParameter("maxIdleTime");
            if (max != null)
                LOG.warn("'maxIdleTime' init param is deprecated, use 'idleTimeout' instead");
        }
        if (max != null)
            defaultCustomizer.setIdleTimeout(Duration.ofMillis(Long.parseLong(max)));

        max = config.getInitParameter("maxTextMessageSize");
        if (max != null)
            defaultCustomizer.setMaxTextMessageSize(Integer.parseInt(max));

        max = config.getInitParameter("maxBinaryMessageSize");
        if (max != null)
            defaultCustomizer.setMaxBinaryMessageSize(Integer.parseInt(max));

        max = config.getInitParameter("inputBufferSize");
        if (max != null)
            defaultCustomizer.setInputBufferSize(Integer.parseInt(max));

        max = config.getInitParameter("outputBufferSize");
        if (max != null)
            defaultCustomizer.setOutputBufferSize(Integer.parseInt(max));

        max = config.getInitParameter("maxFrameSize");
        if (max == null)
            max = config.getInitParameter("maxAllowedFrameSize");
        if (max != null)
            defaultCustomizer.setMaxFrameSize(Long.parseLong(max));

        String autoFragment = config.getInitParameter("autoFragment");
        if (autoFragment != null)
            defaultCustomizer.setAutoFragment(Boolean.parseBoolean(autoFragment));
    }

    @Override
    public void destroy()
    {
    }
}

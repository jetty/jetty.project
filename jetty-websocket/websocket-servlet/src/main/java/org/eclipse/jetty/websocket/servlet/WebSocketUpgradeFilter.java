//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

/**
 * Inline Servlet Filter to capture WebSocket upgrade requests.
 * <p>
 * The configuration applied to this filter via init params will be used as the the default
 * configuration of any websocket upgraded by this filter, prior to the configuration of the
 * websocket applied by the {@link WebSocketMapping}.
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
    private static final Logger LOG = Log.getLogger(WebSocketUpgradeFilter.class);

    private static FilterHolder getFilter(ServletContext servletContext)
    {
        ServletHandler servletHandler = ContextHandler.getContextHandler(servletContext).getChildHandlerByClass(ServletHandler.class);

        for (FilterHolder holder : servletHandler.getFilters())
        {
            if (holder.getInitParameter(MAPPING_ATTRIBUTE_INIT_PARAM) != null)
                return holder;
        }

        return null;
    }

    /**
     * Configure the default WebSocketUpgradeFilter.
     *
     * <p>
     * This will return the default {@link WebSocketUpgradeFilter} on the
     * provided {@link ServletContext}, creating the filter if necessary.
     * </p>
     * <p>
     * The default {@link WebSocketUpgradeFilter} is also available via
     * the {@link ServletContext} attribute named {@code org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter}
     * </p>
     *
     * @param servletContext the {@link ServletContext} to use
     * @return the configured default {@link WebSocketUpgradeFilter} instance
     */
    public static FilterHolder ensureFilter(ServletContext servletContext)
    {
        FilterHolder existingFilter = WebSocketUpgradeFilter.getFilter(servletContext);
        if (existingFilter != null)
            return existingFilter;

        final String name = "WebSocketUpgradeFilter";
        final String pathSpec = "/*";
        FilterHolder holder = new FilterHolder(new WebSocketUpgradeFilter());
        holder.setName(name);
        holder.setInitParameter(MAPPING_ATTRIBUTE_INIT_PARAM, WebSocketMapping.DEFAULT_KEY);

        holder.setAsyncSupported(true);
        ServletHandler servletHandler = ContextHandler.getContextHandler(servletContext).getChildHandlerByClass(ServletHandler.class);
        servletHandler.addFilterWithMapping(holder, pathSpec, EnumSet.of(DispatcherType.REQUEST));
        if (LOG.isDebugEnabled())
            LOG.debug("Adding {} mapped to {} in {}", holder, pathSpec, servletContext);
        return holder;
    }

    public static final String MAPPING_ATTRIBUTE_INIT_PARAM = "org.eclipse.jetty.websocket.servlet.WebSocketMapping.key";

    private final FrameHandler.ConfigurationCustomizer defaultCustomizer = new FrameHandler.ConfigurationCustomizer();
    private WebSocketMapping mapping;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
    {
        HttpServletRequest httpreq = (HttpServletRequest)request;
        HttpServletResponse httpresp = (HttpServletResponse)response;

        if (mapping.upgrade(httpreq, httpresp, defaultCustomizer))
            return;

        // If we reach this point, it means we had an incoming request to upgrade
        // but it was either not a proper websocket upgrade, or it was possibly rejected
        // due to incoming request constraints (controlled by WebSocketCreator)
        if (response.isCommitted())
            return;

        // Handle normally
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
        Dumpable.dumpObjects(out, indent, this, mapping);
    }

    @ManagedAttribute(value = "factory", readonly = true)
    public WebSocketMapping getMapping()
    {
        return mapping;
    }

    @Override
    public void init(FilterConfig config) throws ServletException
    {
        final ServletContext context = config.getServletContext();

        String mappingKey = config.getInitParameter(MAPPING_ATTRIBUTE_INIT_PARAM);
        if (mappingKey != null)
            mapping = WebSocketMapping.ensureMapping(context, mappingKey);
        else
            mapping = new WebSocketMapping(WebSocketComponents.ensureWebSocketComponents(context));

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

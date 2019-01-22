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
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketResources;

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
 * <dt>maxIdleTime</dt>
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


    public static FilterHolder getFilter(ServletContext servletContext)
    {
        ServletHandler servletHandler = ContextHandler.getContextHandler(servletContext).getChildHandlerByClass(ServletHandler.class);

        for (FilterHolder holder : servletHandler.getFilters())
        {
            if (holder.getClassName().equals(WebSocketUpgradeFilter.class.getName()) ||
                    (holder.getHeldClass() != null && WebSocketUpgradeFilter.class.isAssignableFrom(holder.getHeldClass())))
            {
                for (FilterMapping mapping : servletHandler.getFilterMappings())
                {
                    if (mapping.getFilterName().equals(holder.getName()))
                    {
                        for (String path : mapping.getPathSpecs())
                        {
                            if (path.equals("/") || path.equals("/*"))
                                return holder;
                        }
                    }
                }
            }
        }

        return null;
    }

    public static WebSocketMapping getMapping(ServletContext servletContext)
    {
        FilterHolder existingFilter = WebSocketUpgradeFilter.getFilter(servletContext);
        if (existingFilter == null)
            return null;

        return ((WebSocketUpgradeFilter)existingFilter.getFilter()).getMapping();
    }

    public static FilterHolder ensureFilter(ServletContext servletContext)
    {
        FilterHolder existingFilter = WebSocketUpgradeFilter.getFilter(servletContext);
        if (existingFilter != null)
            return existingFilter;

        String name = "WebSocketUpgradeFilter";
        // TODO this should be registered at pathSpec "/" (NOTE: also remove from getFilter)
        String pathSpec = "/*";
        EnumSet<DispatcherType> dispatcherTypes = EnumSet.of(DispatcherType.REQUEST);
        FilterHolder holder = new FilterHolder(new WebSocketUpgradeFilter());
        holder.setName(name);

        holder.setAsyncSupported(true);
        ServletHandler servletHandler = ContextHandler.getContextHandler(servletContext).getChildHandlerByClass(ServletHandler.class);
        servletHandler.addFilterWithMapping(holder, pathSpec, dispatcherTypes);
        if (LOG.isDebugEnabled())
            LOG.debug("Adding {} mapped to {} in {}", holder, pathSpec, servletContext);
        return holder;
    }

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

    public void setMapping(WebSocketMapping mapping)
    {
        this.mapping = mapping;
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

        if (mapping == null)
            mapping = new WebSocketMapping(WebSocketResources.ensureWebSocketResources(context));

        String max = config.getInitParameter("maxIdleTime");
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
}

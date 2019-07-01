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
import java.lang.reflect.Constructor;
import java.time.Duration;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

/**
 * Abstract Servlet used to bridge the Servlet API to the WebSocket API.
 * <p>
 * To use this servlet, you will be required to register your websockets with the {@link WebSocketMapping} so that it can create your websockets under the
 * appropriate conditions.
 * </p>
 * <p>The most basic implementation would be as follows:</p>
 * <pre>
 * package my.example;
 *
 * import WebSocketServlet;
 * import WebSocketServletFactory;
 *
 * public class MyEchoServlet extends WebSocketServlet
 * {
 *     &#064;Override
 *     public void configure(WebSocketServletFactory factory)
 *     {
 *       factory.setDefaultMaxFrameSize(4096);
 *       factory.addMapping(factory.parsePathSpec("/"), (req,res)-&gt;new EchoSocket());
 *     }
 * }
 * </pre>
 * <p>
 * Only request that conforms to a "WebSocket: Upgrade" handshake request will trigger the {@link WebSocketMapping} handling of creating
 * WebSockets.  All other requests are treated as normal servlet requests.  The configuration defined by this servlet init parameters will
 * be used as the customizer for any mappings created by {@link WebSocketServletFactory#addMapping(PathSpec, WebSocketCreator)} during
 * {@link #configure(WebSocketServletFactory)} calls.  The request upgrade may be peformed by this servlet, or is may be performed by a
 * {@link WebSocketUpgradeFilter} instance that will share the same {@link WebSocketMapping} instance.  If the filter is used, then the
 * filter configuraton is used as the default configuration prior to this servlets configuration being applied.
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
 * <dd>set the size in bytes of the buffer used to read raw bytes from the network layer<br> * <dt>outputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to write bytes to the network layer<br>
 * <dt>maxFrameSize</dt>
 * <dd>The maximum frame size sent or received.<br>
 * <dt>autoFragment</dt>
 * <dd>If true, frames are automatically fragmented to respect the maximum frame size.<br>
 * </dl>
 */
@SuppressWarnings("serial")
public abstract class WebSocketServlet extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(WebSocketServlet.class);
    private final CustomizedWebSocketServletFactory customizer = new CustomizedWebSocketServletFactory();

    private WebSocketMapping mapping;
    private WebSocketComponents components;

    /**
     * Configure the WebSocketServletFactory for this servlet instance by setting default
     * configuration (which may be overriden by annotations) and mapping {@link WebSocketCreator}s.
     * This method assumes a single {@link FrameHandlerFactory} will be available as a bean on the
     * {@link ContextHandler}, which in practise will mostly the the Jetty WebSocket API factory.
     *
     * @param factory the WebSocketServletFactory
     */
    protected abstract void configure(WebSocketServletFactory factory);

    /**
     * @return the instance of {@link FrameHandlerFactory} to be used to create the FrameHandler
     */
    protected abstract FrameHandlerFactory getFactory();

    @Override
    public void init() throws ServletException
    {
        try
        {
            ServletContext servletContext = getServletContext();

            components = WebSocketComponents.ensureWebSocketComponents(servletContext);
            mapping = new WebSocketMapping(components);

            String max = getInitParameter("idleTimeout");
            if (max == null)
            {
                max = getInitParameter("maxIdleTime");
                if (max != null)
                    LOG.warn("'maxIdleTime' init param is deprecated, use 'idleTimeout' instead");
            }
            if (max != null)
                customizer.setIdleTimeout(Duration.ofMillis(Long.parseLong(max)));

            max = getInitParameter("maxTextMessageSize");
            if (max != null)
                customizer.setMaxTextMessageSize(Long.parseLong(max));

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null)
                customizer.setMaxBinaryMessageSize(Long.parseLong(max));

            max = getInitParameter("inputBufferSize");
            if (max != null)
                customizer.setInputBufferSize(Integer.parseInt(max));

            max = getInitParameter("outputBufferSize");
            if (max != null)
                customizer.setOutputBufferSize(Integer.parseInt(max));

            max = getInitParameter("maxFrameSize");
            if (max == null)
                max = getInitParameter("maxAllowedFrameSize");
            if (max != null)
                customizer.setMaxFrameSize(Long.parseLong(max));

            String autoFragment = getInitParameter("autoFragment");
            if (autoFragment != null)
                customizer.setAutoFragment(Boolean.parseBoolean(autoFragment));

            configure(customizer); // Let user modify customizer prior after init params
        }
        catch (Throwable x)
        {
            throw new ServletException(x);
        }
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException
    {
        // provide a null default customizer the customizer will be on the negotiator in the mapping
        if (mapping.upgrade(req, resp, null))
            return;

        // If we reach this point, it means we had an incoming request to upgrade
        // but it was either not a proper websocket upgrade, or it was possibly rejected
        // due to incoming request constraints (controlled by WebSocketCreator)
        if (resp.isCommitted())
            return;

        // Handle normally
        super.service(req, resp);
    }

    private class CustomizedWebSocketServletFactory extends FrameHandler.ConfigurationCustomizer implements WebSocketServletFactory
    {
        @Override
        public WebSocketExtensionRegistry getExtensionRegistry()
        {
            return components.getExtensionRegistry();
        }

        @Override
        public void addMapping(String pathSpec, WebSocketCreator creator)
        {
            addMapping(WebSocketMapping.parsePathSpec(pathSpec), creator);
        }

        @Override
        public void addMapping(PathSpec pathSpec, WebSocketCreator creator)
        {
            mapping.addMapping(pathSpec, creator, getFactory(), this);
        }

        @Override
        public void register(Class<?> endpointClass)
        {
            Constructor<?> constructor;
            try
            {
                constructor = endpointClass.getDeclaredConstructor();
            }
            catch (NoSuchMethodException e)
            {
                throw new RuntimeException(e);
            }

            WebSocketCreator creator = (req, resp) ->
            {
                try
                {
                    return constructor.newInstance();
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                    return null;
                }
            };

            addMapping("/", creator);
        }

        @Override
        public void setCreator(WebSocketCreator creator)
        {
            addMapping("/", creator);
        }

        @Override
        public WebSocketCreator getMapping(PathSpec pathSpec)
        {
            return mapping.getMapping(pathSpec);
        }

        @Override
        public WebSocketCreator getMatch(String target)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeMapping(PathSpec pathSpec)
        {
            return mapping.removeMapping(pathSpec);
        }

        @Override
        public PathSpec parsePathSpec(String pathSpec)
        {
            return WebSocketMapping.parsePathSpec(pathSpec);
        }
    }
}

//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.servlet.ServletContext;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.util.ReflectUtils;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.internal.DelegatedServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.internal.DelegatedServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.internal.JettyServerFrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketUpgradeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketServerContainer extends ContainerLifeCycle implements WebSocketContainer, WebSocketPolicy, LifeCycle.Listener
{
    public static final String JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE = WebSocketContainer.class.getName();

    public static JettyWebSocketServerContainer getContainer(ServletContext servletContext)
    {
        return (JettyWebSocketServerContainer)servletContext.getAttribute(JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE);
    }

    public static JettyWebSocketServerContainer ensureContainer(ServletContext servletContext)
    {
        ServletContextHandler contextHandler = ServletContextHandler.getServletContextHandler(servletContext, "Javax Websocket");
        if (contextHandler.getServer() == null)
            throw new IllegalStateException("Server has not been set on the ServletContextHandler");

        JettyWebSocketServerContainer container = getContainer(servletContext);
        if (container == null)
        {
            // Find Pre-Existing executor
            Executor executor = (Executor)servletContext.getAttribute("org.eclipse.jetty.server.Executor");
            if (executor == null)
                executor = contextHandler.getServer().getThreadPool();

            // Create the Jetty ServerContainer implementation
            WebSocketMappings mappings = WebSocketMappings.ensureMappings(servletContext);
            container = new JettyWebSocketServerContainer(contextHandler, mappings, executor);
            servletContext.setAttribute(JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE, container);
            contextHandler.addManaged(container);
            contextHandler.addEventListener(container);
        }

        return container;
    }

    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketServerContainer.class);

    private final ServletContextHandler contextHandler;
    private final WebSocketMappings webSocketMappings;
    private final FrameHandlerFactory frameHandlerFactory;
    private final Executor executor;
    private final Configuration.ConfigurationCustomizer customizer = new Configuration.ConfigurationCustomizer();

    private final List<WebSocketSessionListener> sessionListeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();

    /**
     * Main entry point for {@link JettyWebSocketServletContainerInitializer}.
     *
     * @param webSocketMappings the {@link WebSocketMappings} that this container belongs to
     * @param executor the {@link Executor} to use
     */
    JettyWebSocketServerContainer(ServletContextHandler contextHandler, WebSocketMappings webSocketMappings, Executor executor)
    {
        this.contextHandler = contextHandler;
        this.webSocketMappings = webSocketMappings;
        this.executor = executor;

        // Ensure there is a FrameHandlerFactory
        JettyServerFrameHandlerFactory factory = contextHandler.getBean(JettyServerFrameHandlerFactory.class);
        if (factory == null)
        {
            factory = new JettyServerFrameHandlerFactory(this);
            contextHandler.addManaged(factory);
            contextHandler.addEventListener(factory);
        }
        frameHandlerFactory = factory;

        addSessionListener(sessionTracker);
        addBean(sessionTracker);
    }

    public void addMapping(String pathSpec, JettyWebSocketCreator creator)
    {
        PathSpec ps = WebSocketMappings.parsePathSpec(pathSpec);
        if (webSocketMappings.getWebSocketNegotiator(ps) != null)
            throw new WebSocketException("Duplicate WebSocket Mapping for PathSpec");

        WebSocketUpgradeFilter.ensureFilter(contextHandler.getServletContext());
        webSocketMappings.addMapping(ps,
            (req, resp) -> creator.createWebSocket(new DelegatedServerUpgradeRequest(req), new DelegatedServerUpgradeResponse(resp)),
            frameHandlerFactory, customizer);
    }

    public void addMapping(String pathSpec, final Class<?> endpointClass)
    {
        if (!ReflectUtils.isDefaultConstructable(endpointClass))
            throw new IllegalArgumentException("Cannot access default constructor for the class: " + endpointClass.getName());

        addMapping(pathSpec, (req, resp) ->
        {
            try
            {
                return endpointClass.getDeclaredConstructor().newInstance();
            }
            catch (Exception e)
            {
                throw new org.eclipse.jetty.websocket.api.exceptions.WebSocketException("Unable to create instance of " + endpointClass.getName(), e);
            }
        });
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        sessionListeners.add(listener);
    }

    @Override
    public boolean removeSessionListener(WebSocketSessionListener listener)
    {
        return sessionListeners.remove(listener);
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> consumer)
    {
        for (WebSocketSessionListener listener : sessionListeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener {}", listener, x);
            }
        }
    }

    @Override
    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }

    @Override
    public WebSocketBehavior getBehavior()
    {
        return WebSocketBehavior.SERVER;
    }

    @Override
    public Duration getIdleTimeout()
    {
        return customizer.getIdleTimeout();
    }

    @Override
    public int getInputBufferSize()
    {
        return customizer.getInputBufferSize();
    }

    @Override
    public int getOutputBufferSize()
    {
        return customizer.getOutputBufferSize();
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return customizer.getMaxBinaryMessageSize();
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return customizer.getMaxTextMessageSize();
    }

    @Override
    public long getMaxFrameSize()
    {
        return customizer.getMaxFrameSize();
    }

    @Override
    public boolean isAutoFragment()
    {
        return customizer.isAutoFragment();
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        customizer.setIdleTimeout(duration);
    }

    @Override
    public void setInputBufferSize(int size)
    {
        customizer.setInputBufferSize(size);
    }

    @Override
    public void setOutputBufferSize(int size)
    {
        customizer.setOutputBufferSize(size);
    }

    @Override
    public void setMaxBinaryMessageSize(long size)
    {
        customizer.setMaxBinaryMessageSize(size);
    }

    @Override
    public void setMaxTextMessageSize(long size)
    {
        customizer.setMaxTextMessageSize(size);
    }

    @Override
    public void setMaxFrameSize(long maxFrameSize)
    {
        customizer.setMaxFrameSize(maxFrameSize);
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {
        customizer.setAutoFragment(autoFragment);
    }
}

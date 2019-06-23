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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.internal.JettyServerFrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketMapping;

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
            container = new JettyWebSocketServerContainer(
                contextHandler,
                WebSocketMapping.ensureMapping(servletContext, WebSocketMapping.DEFAULT_KEY),
                WebSocketComponents.ensureWebSocketComponents(servletContext), executor);
            servletContext.setAttribute(JETTY_WEBSOCKET_CONTAINER_ATTRIBUTE, container);
            contextHandler.addManaged(container);
            contextHandler.addLifeCycleListener(container);
        }

        return container;
    }

    private static final Logger LOG = Log.getLogger(JettyWebSocketServerContainer.class);

    private final WebSocketMapping webSocketMapping;
    private final WebSocketComponents webSocketComponents;
    private final FrameHandlerFactory frameHandlerFactory;
    private final Executor executor;
    private final FrameHandler.ConfigurationCustomizer customizer = new FrameHandler.ConfigurationCustomizer();

    private final List<WebSocketSessionListener> sessionListeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();

    /**
     * Main entry point for {@link JettyWebSocketServletContainerInitializer}.
     *
     * @param webSocketMapping the {@link WebSocketMapping} that this container belongs to
     * @param webSocketComponents the {@link WebSocketComponents} instance to use
     * @param executor the {@link Executor} to use
     */
    JettyWebSocketServerContainer(ServletContextHandler contextHandler, WebSocketMapping webSocketMapping, WebSocketComponents webSocketComponents, Executor executor)
    {
        this.webSocketMapping = webSocketMapping;
        this.webSocketComponents = webSocketComponents;
        this.executor = executor;

        // Ensure there is a FrameHandlerFactory
        JettyServerFrameHandlerFactory factory = contextHandler.getBean(JettyServerFrameHandlerFactory.class);
        if (factory == null)
        {
            factory = new JettyServerFrameHandlerFactory(this);
            contextHandler.addManaged(factory);
            contextHandler.addLifeCycleListener(factory);
        }
        frameHandlerFactory = factory;

        addSessionListener(sessionTracker);
    }

    public void addMapping(String pathSpec, JettyWebSocketCreator creator)
    {
        PathSpec ps = WebSocketMapping.parsePathSpec(pathSpec);
        if (webSocketMapping.getMapping(ps) != null)
            throw new WebSocketException("Duplicate WebSocket Mapping for PathSpec");

        webSocketMapping.addMapping(ps,
            (req, resp) -> creator.createWebSocket(new JettyServerUpgradeRequest(req), new JettyServerUpgradeResponse(resp)),
            frameHandlerFactory, customizer);
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
                LOG.info("Exception while invoking listener " + listener, x);
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
}

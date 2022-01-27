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

package org.eclipse.jetty.websocket.core.server;

import java.util.Objects;
import java.util.concurrent.Executor;
import javax.servlet.ServletContext;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

/**
 * A collection of components which are the resources needed for websockets such as
 * {@link ByteBufferPool}, {@link WebSocketExtensionRegistry}, and {@link DecoratedObjectFactory}.
 *
 * These components should be accessed through {@link WebSocketServerComponents#getWebSocketComponents} so that
 * the instance can be shared by being stored as a bean on the ContextHandler.
 */
public class WebSocketServerComponents extends WebSocketComponents
{
    public static final String WEBSOCKET_COMPONENTS_ATTRIBUTE = WebSocketComponents.class.getName();
    public static final String WEBSOCKET_INFLATER_POOL_ATTRIBUTE = "jetty.websocket.inflater";
    public static final String WEBSOCKET_DEFLATER_POOL_ATTRIBUTE = "jetty.websocket.deflater";
    public static final String WEBSOCKET_BUFFER_POOL_ATTRIBUTE = "jetty.websocket.bufferPool";

    WebSocketServerComponents(InflaterPool inflaterPool, DeflaterPool deflaterPool, ByteBufferPool bufferPool, DecoratedObjectFactory objectFactory, Executor executor)
    {
        super(null, objectFactory, bufferPool, inflaterPool, deflaterPool, executor);
    }

    /**
     * <p>
     * This ensures a {@link WebSocketComponents} is available at the {@link ServletContext} attribute {@link #WEBSOCKET_COMPONENTS_ATTRIBUTE}.
     * </p>
     * <p>
     * This should be called when the server is starting, usually by a {@link javax.servlet.ServletContainerInitializer}.
     * </p>
     * <p>
     * Servlet context attributes can be set with {@link #WEBSOCKET_BUFFER_POOL_ATTRIBUTE}, {@link #WEBSOCKET_INFLATER_POOL_ATTRIBUTE}
     * and {@link #WEBSOCKET_DEFLATER_POOL_ATTRIBUTE} to override the {@link ByteBufferPool}, {@link DeflaterPool} or
     * {@link InflaterPool} used by the components, otherwise this will try to use the pools shared on the {@link Server}.
     * </p>
     * @param server the server.
     * @param servletContext the ServletContext.
     * @return the WebSocketComponents that was created or found on the ServletContext.
     */
    public static WebSocketComponents ensureWebSocketComponents(Server server, ServletContext servletContext)
    {
        WebSocketComponents components = (WebSocketComponents)servletContext.getAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE);
        if (components != null)
            return components;

        InflaterPool inflaterPool = (InflaterPool)servletContext.getAttribute(WEBSOCKET_INFLATER_POOL_ATTRIBUTE);
        if (inflaterPool == null)
            inflaterPool = InflaterPool.ensurePool(server);

        DeflaterPool deflaterPool = (DeflaterPool)servletContext.getAttribute(WEBSOCKET_DEFLATER_POOL_ATTRIBUTE);
        if (deflaterPool == null)
            deflaterPool = DeflaterPool.ensurePool(server);

        ByteBufferPool bufferPool = (ByteBufferPool)servletContext.getAttribute(WEBSOCKET_BUFFER_POOL_ATTRIBUTE);
        if (bufferPool == null)
            bufferPool = server.getBean(ByteBufferPool.class);

        Executor executor = (Executor)servletContext.getAttribute("org.eclipse.jetty.server.Executor");
        if (executor == null)
            executor = server.getThreadPool();

        DecoratedObjectFactory objectFactory = (DecoratedObjectFactory)servletContext.getAttribute(DecoratedObjectFactory.ATTR);
        WebSocketComponents serverComponents = new WebSocketServerComponents(inflaterPool, deflaterPool, bufferPool, objectFactory, executor);
        if (objectFactory != null)
            serverComponents.unmanage(objectFactory);

        // These components may be managed by the server but not yet started.
        // In this case we don't want them to be managed by the components as well.
        if (server.contains(inflaterPool))
            serverComponents.unmanage(inflaterPool);
        if (server.contains(deflaterPool))
            serverComponents.unmanage(deflaterPool);
        if (server.contains(bufferPool))
            serverComponents.unmanage(bufferPool);
        if (executor != null)
            serverComponents.unmanage(executor);

        // Stop the WebSocketComponents when the ContextHandler stops.
        ContextHandler contextHandler = Objects.requireNonNull(ContextHandler.getContextHandler(servletContext));
        contextHandler.addManaged(serverComponents);
        contextHandler.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStopping(LifeCycle event)
            {
                servletContext.removeAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE);
                contextHandler.removeBean(serverComponents);
                contextHandler.removeEventListener(this);
            }

            @Override
            public String toString()
            {
                return String.format("%sCleanupListener", WebSocketServerComponents.class.getSimpleName());
            }
        });

        servletContext.setAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE, serverComponents);
        return serverComponents;
    }

    public static WebSocketComponents getWebSocketComponents(ServletContext servletContext)
    {
        WebSocketComponents components = (WebSocketComponents)servletContext.getAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE);
        if (components == null)
            throw new IllegalStateException("WebSocketComponents has not been created");

        return components;
    }
}

//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import javax.servlet.ServletContext;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.DecoratedObjectFactory;
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

    WebSocketServerComponents(InflaterPool inflaterPool, DeflaterPool deflaterPool, ByteBufferPool bufferPool)
    {
        super(null, null, bufferPool, inflaterPool, deflaterPool);
    }

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

        components = new WebSocketServerComponents(inflaterPool, deflaterPool, bufferPool);
        servletContext.setAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE, components);
        return components;
    }

    public static WebSocketComponents getWebSocketComponents(ServletContext servletContext)
    {
        WebSocketComponents components = (WebSocketComponents)servletContext.getAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE);
        if (components == null)
            throw new IllegalStateException("WebSocketComponents has not been created");

        return components;
    }
}

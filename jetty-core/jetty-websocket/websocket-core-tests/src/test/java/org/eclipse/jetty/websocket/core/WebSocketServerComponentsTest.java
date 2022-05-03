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

package org.eclipse.jetty.websocket.core;

import java.util.zip.Deflater;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketServerComponentsTest
{
    private Server server;
    private ContextHandler contextHandler;
    private WebSocketComponents components;

    @BeforeEach
    public void before()
    {
        server = new Server();
        contextHandler = new ContextHandler();
        server.setHandler(contextHandler);
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
    }

    @Test
    public void testComponentsInsideServletContainerInitializer() throws Exception
    {
        // ensureWebSocketComponents can only be called when the server is starting.
        contextHandler.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStarting(LifeCycle event)
            {
                components = WebSocketServerComponents.ensureWebSocketComponents(server, contextHandler);
            }
        });

        // Components is created only when the server is started.
        assertNull(components);
        server.start();
        assertNotNull(components);

        // Components is started when it is created.
        assertTrue(components.isStarted());
        DeflaterPool deflaterPool = components.getDeflaterPool();
        InflaterPool inflaterPool = components.getInflaterPool();

        // The components is stopped with the ServletContext.
        contextHandler.stop();
        assertTrue(components.isStopped());

        // By default the default CompressionPools from the server are used, these should not be stopped with the context.
        assertTrue(inflaterPool.isStarted());
        assertTrue(deflaterPool.isStarted());
    }

    @Test
    public void testCompressionPoolsManagedByContext() throws Exception
    {
        ContextHandler.Context context = contextHandler.getContext();

        // Use a custom InflaterPool and DeflaterPool that are not started or managed.
        InflaterPool inflaterPool = new InflaterPool(333, false);
        DeflaterPool deflaterPool = new DeflaterPool(333, Deflater.BEST_SPEED, false);
        context.setAttribute(WebSocketServerComponents.WEBSOCKET_DEFLATER_POOL_ATTRIBUTE, deflaterPool);
        context.setAttribute(WebSocketServerComponents.WEBSOCKET_INFLATER_POOL_ATTRIBUTE, inflaterPool);

        // ensureWebSocketComponents can only be called when the server is starting.
        contextHandler.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStarting(LifeCycle event)
            {
                components = WebSocketServerComponents.ensureWebSocketComponents(server, contextHandler);
            }
        });

        // Components is created only when the server is started.
        assertNull(components);
        server.start();
        assertNotNull(components);

        // Components is started when it is created.
        assertTrue(components.isStarted());

        // The components uses the CompressionPools set as servletContext attributes.
        assertThat(components.getInflaterPool(), is(inflaterPool));
        assertThat(components.getDeflaterPool(), is(deflaterPool));
        assertTrue(inflaterPool.isStarted());
        assertTrue(deflaterPool.isStarted());

        // The components is stopped with the ServletContext.
        contextHandler.stop();
        assertTrue(components.isStopped());

        // The inflater and deflater pools are stopped as they are not managed by the server.
        assertTrue(inflaterPool.isStopped());
        assertTrue(deflaterPool.isStopped());
    }

    @Test
    public void testCompressionPoolsManagedByServer() throws Exception
    {
        // Use a custom InflaterPool and DeflaterPool that are not started or managed.
        InflaterPool inflaterPool = new InflaterPool(333, false);
        DeflaterPool deflaterPool = new DeflaterPool(333, Deflater.BEST_SPEED, false);
        server.addBean(inflaterPool);
        server.addBean(deflaterPool);

        // ensureWebSocketComponents can only be called when the server is starting.
        contextHandler.addEventListener(new LifeCycle.Listener()
        {
            @Override
            public void lifeCycleStarting(LifeCycle event)
            {
                components = WebSocketServerComponents.ensureWebSocketComponents(server, contextHandler);
            }
        });

        // The CompressionPools will only be started with the server.
        assertTrue(inflaterPool.isStopped());
        assertTrue(deflaterPool.isStopped());
        server.start();
        assertThat(components.getInflaterPool(), is(inflaterPool));
        assertThat(components.getDeflaterPool(), is(deflaterPool));
        assertTrue(inflaterPool.isStarted());
        assertTrue(deflaterPool.isStarted());

        // The components is stopped with the ServletContext, but the CompressionPools are stopped with the server.
        contextHandler.stop();
        assertTrue(components.isStopped());
        assertTrue(inflaterPool.isStarted());
        assertTrue(deflaterPool.isStarted());
        server.stop();
        assertTrue(inflaterPool.isStopped());
        assertTrue(deflaterPool.isStopped());
    }
}

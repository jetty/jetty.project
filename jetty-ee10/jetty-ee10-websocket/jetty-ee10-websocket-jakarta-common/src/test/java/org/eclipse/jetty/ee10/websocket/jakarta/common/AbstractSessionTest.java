//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.common;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractSessionTest
{
    protected JakartaWebSocketContainer container = new DummyContainer();
    protected WebSocketComponents components = new WebSocketComponents();
    protected TestCoreSession coreSession = new TestCoreSession(components);
    protected JakartaWebSocketSession session;

    @BeforeEach
    public void initSession() throws Exception
    {
        container.start();
        components.start();
        Object websocketPojo = new DummyEndpoint();
        UpgradeRequest upgradeRequest = new UpgradeRequestAdapter();
        JakartaWebSocketFrameHandler frameHandler = container.newFrameHandler(websocketPojo, upgradeRequest);
        session = new JakartaWebSocketSession(container, coreSession, frameHandler, container.getFrameHandlerFactory()
            .newDefaultEndpointConfig(websocketPojo.getClass()));
    }

    @AfterEach
    public void stopContainer() throws Exception
    {
        components.stop();
        container.stop();
    }

    public static class TestCoreSession extends CoreSession.Empty
    {
        private final Semaphore demand = new Semaphore(0);
        private final WebSocketComponents components;

        public TestCoreSession(WebSocketComponents components)
        {
            this.components = components;
        }

        @Override
        public WebSocketComponents getWebSocketComponents()
        {
            return components;
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            return components.getByteBufferPool();
        }

        public void waitForDemand(long timeout, TimeUnit timeUnit) throws InterruptedException
        {
            assertTrue(demand.tryAcquire(timeout, timeUnit));
        }

        @Override
        public void demand(long n)
        {
            demand.release();
        }
    }

    public static class DummyEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
        }
    }
}

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

package org.eclipse.jetty.websocket.javax.common;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executor;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Session;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

/**
 * Dummy Container for testing.
 */
public class DummyContainer extends JavaxWebSocketContainer
{
    private final JavaxWebSocketFrameHandlerFactory frameHandlerFactory;
    private final QueuedThreadPool executor;

    public DummyContainer()
    {
        super(new WebSocketComponents());
        this.frameHandlerFactory = new DummyFrameHandlerFactory(this);
        this.executor = new QueuedThreadPool();
        this.executor.setName("qtp-DummyContainer");
        addBean(this.executor, true);
    }

    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return 0;
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis)
    {
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException, IOException
    {
        throw new UnsupportedOperationException("Not supported by DummyContainer");
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException, IOException
    {
        throw new UnsupportedOperationException("Not supported by DummyContainer");
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException
    {
        throw new UnsupportedOperationException("Not supported by DummyContainer");
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException
    {
        throw new UnsupportedOperationException("Not supported by DummyContainer");
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return 0;
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout)
    {
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return 0;
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return 0;
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
    }

    @Override
    public Executor getExecutor()
    {
        return executor;
    }

    @Override
    protected JavaxWebSocketFrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return null;
    }
}

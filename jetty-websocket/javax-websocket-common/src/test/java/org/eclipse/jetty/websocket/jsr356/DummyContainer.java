//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executor;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Session;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

/**
 * Dummy Container for testing.
 */
public class DummyContainer extends JavaxWebSocketContainer
{
    private final JavaxWebSocketFrameHandlerFactory frameHandlerFactory;
    private final ByteBufferPool bufferPool;
    private final QueuedThreadPool executor;
    private final DecoratedObjectFactory objectFactory;

    public DummyContainer()
    {
        this.frameHandlerFactory = new DummyFrameHandlerFactory(this);
        this.bufferPool = new MappedByteBufferPool();
        this.objectFactory = new DecoratedObjectFactory();
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
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.objectFactory;
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
    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
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
    protected WebSocketExtensionRegistry getExtensionRegistry()
    {
        return null;
    }
}

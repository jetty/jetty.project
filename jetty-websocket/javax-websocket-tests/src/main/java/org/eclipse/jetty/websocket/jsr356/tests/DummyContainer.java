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

package org.eclipse.jetty.websocket.jsr356.tests;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Session;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandlerFactory;

/**
 * Dummy Container for testing.
 */
public class DummyContainer extends JavaxWebSocketContainer
{
    private final ByteBufferPool bufferPool;
    private final ClassLoader contextClassLoader;
    private final Executor executor;
    private final WebSocketExtensionRegistry extensionRegistry;
    private final DecoratedObjectFactory objectFactory;
    private JavaxWebSocketFrameHandlerFactory frameHandlerFactory;

    public DummyContainer()
    {
        this.bufferPool = new MappedByteBufferPool();
        this.contextClassLoader = this.getClass().getClassLoader();
        this.executor = Executors.newFixedThreadPool(10);
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.objectFactory = new DecoratedObjectFactory();
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
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    @Override
    protected JavaxWebSocketFrameHandlerFactory getFrameHandlerFactory()
    {
        Objects.requireNonNull(frameHandlerFactory, "JavaxWebSocketFrameHandlerFactory not specified in DummyContainer properly");
        return frameHandlerFactory;
    }

    public void setFrameHandlerFactory(JavaxWebSocketFrameHandlerFactory frameHandlerFactory)
    {
        this.frameHandlerFactory = frameHandlerFactory;
    }
}

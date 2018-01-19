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

package org.eclipse.jetty.websocket.common;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

/**
 * Dummy Container for testing.
 */
public class DummyContainer extends ContainerLifeCycle implements WebSocketContainerContext
{
    private final ByteBufferPool bufferPool;
    private final ClassLoader contextClassLoader;
    private final Executor executor;
    private final WebSocketExtensionRegistry extensionRegistry;
    private final DecoratedObjectFactory objectFactory;
    private final WebSocketPolicy policy;

    public DummyContainer(WebSocketPolicy policy)
    {
        this.policy = policy;
        this.bufferPool = new MappedByteBufferPool();
        this.contextClassLoader = this.getClass().getClassLoader();
        this.executor = Executors.newFixedThreadPool(10);
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.objectFactory = new DecoratedObjectFactory();
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    @Override
    public ClassLoader getContextClassloader()
    {
        return contextClassLoader;
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
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }
}

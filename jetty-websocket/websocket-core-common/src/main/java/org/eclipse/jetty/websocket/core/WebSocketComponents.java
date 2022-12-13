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

import java.util.concurrent.Executor;
import java.util.zip.Deflater;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/**
 * A collection of components which are the resources needed for websockets such as
 * {@link ByteBufferPool}, {@link WebSocketExtensionRegistry}, and {@link DecoratedObjectFactory}.
 */
public class WebSocketComponents extends ContainerLifeCycle
{
    private final DecoratedObjectFactory _objectFactory;
    private final WebSocketExtensionRegistry _extensionRegistry;
    private final Executor _executor;
    private final ByteBufferPool _bufferPool;
    private final InflaterPool _inflaterPool;
    private final DeflaterPool _deflaterPool;

    public WebSocketComponents()
    {
        this(null, null, null, null, null);
    }

    public WebSocketComponents(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory,
                               ByteBufferPool bufferPool, InflaterPool inflaterPool, DeflaterPool deflaterPool)
    {
        this (extensionRegistry, objectFactory, bufferPool, inflaterPool, deflaterPool, null);
    }

    public WebSocketComponents(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory,
                               ByteBufferPool bufferPool, InflaterPool inflaterPool, DeflaterPool deflaterPool, Executor executor)
    {
        _extensionRegistry = (extensionRegistry == null) ? new WebSocketExtensionRegistry() : extensionRegistry;
        _objectFactory = (objectFactory == null) ? new DecoratedObjectFactory() : objectFactory;
        _bufferPool = (bufferPool == null) ? new MappedByteBufferPool() : bufferPool;
        _inflaterPool = (inflaterPool == null) ? new InflaterPool(CompressionPool.DEFAULT_CAPACITY, true) : inflaterPool;
        _deflaterPool = (deflaterPool == null) ? new DeflaterPool(CompressionPool.DEFAULT_CAPACITY, Deflater.DEFAULT_COMPRESSION, true) : deflaterPool;

        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName("WebSocket@" + hashCode());
            _executor = threadPool;
        }
        else
        {
            _executor = executor;
        }

        addBean(_inflaterPool);
        addBean(_deflaterPool);
        addBean(_bufferPool);
        addBean(_extensionRegistry);
        addBean(_objectFactory);
        addBean(_executor);
    }

    public ByteBufferPool getBufferPool()
    {
        return _bufferPool;
    }

    public Executor getExecutor()
    {
        return _executor;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return _extensionRegistry;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return _objectFactory;
    }

    public InflaterPool getInflaterPool()
    {
        return _inflaterPool;
    }

    public DeflaterPool getDeflaterPool()
    {
        return _deflaterPool;
    }
}

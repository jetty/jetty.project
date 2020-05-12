//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.util.zip.Deflater;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;

/**
 * A collection of components which are the resources needed for websockets such as
 * {@link ByteBufferPool}, {@link WebSocketExtensionRegistry}, and {@link DecoratedObjectFactory}.
 */
public class WebSocketComponents
{
    private final DecoratedObjectFactory objectFactory;
    private final WebSocketExtensionRegistry extensionRegistry;
    private final ByteBufferPool bufferPool;
    private final InflaterPool inflaterPool;
    private final DeflaterPool deflaterPool;

    public WebSocketComponents()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool(),
            new InflaterPool(CompressionPool.INFINITE_CAPACITY, true),
            new DeflaterPool(CompressionPool.INFINITE_CAPACITY, Deflater.DEFAULT_COMPRESSION, true));
    }

    public WebSocketComponents(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory,
                               ByteBufferPool bufferPool, InflaterPool inflaterPool, DeflaterPool deflaterPool)
    {
        this.extensionRegistry = extensionRegistry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;
        this.deflaterPool = deflaterPool;
        this.inflaterPool = inflaterPool;
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public InflaterPool getInflaterPool()
    {
        return inflaterPool;
    }

    public DeflaterPool getDeflaterPool()
    {
        return deflaterPool;
    }
}

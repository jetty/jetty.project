//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.util.zip.Deflater;
import javax.servlet.ServletContext;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;

/**
 * A collection of components which are the resources needed for websockets such as
 * {@link ByteBufferPool}, {@link WebSocketExtensionRegistry}, and {@link DecoratedObjectFactory}.
 *
 * These components should be accessed through {@link WebSocketComponents#ensureWebSocketComponents} so that
 * the instance can be shared by being stored as a bean on the ContextHandler.
 */
public class WebSocketComponents
{
    public static final String WEBSOCKET_COMPONENTS_ATTRIBUTE = WebSocketComponents.class.getName();

    public static WebSocketComponents ensureWebSocketComponents(ServletContext servletContext)
    {
        // Ensure a mapping exists
        WebSocketComponents components = (WebSocketComponents)servletContext.getAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE);
        if (components == null)
        {
            components = new WebSocketComponents();
            servletContext.setAttribute(WEBSOCKET_COMPONENTS_ATTRIBUTE, components);
        }

        return components;
    }

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

    private DecoratedObjectFactory objectFactory;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;
    private InflaterPool inflaterPool;
    private DeflaterPool deflaterPool;

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
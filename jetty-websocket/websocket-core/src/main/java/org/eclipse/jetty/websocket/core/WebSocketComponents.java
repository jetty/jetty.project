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

import javax.servlet.ServletContext;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;

public class WebSocketComponents
{
    public static WebSocketComponents ensureWebSocketComponents(ServletContext servletContext)
    {
        ContextHandler contextHandler = ContextHandler.getContextHandler(servletContext);

        // Ensure a mapping exists
        WebSocketComponents components = contextHandler.getBean(WebSocketComponents.class);
        if (components == null)
        {
            components = new WebSocketComponents();
            contextHandler.addBean(components);
        }

        return components;
    }

    public WebSocketComponents()
    {
        this(new WebSocketExtensionRegistry(), new DecoratedObjectFactory(), new MappedByteBufferPool());
    }

    public WebSocketComponents(WebSocketExtensionRegistry extensionRegistry, DecoratedObjectFactory objectFactory, ByteBufferPool bufferPool)
    {
        this.extensionRegistry = extensionRegistry;
        this.objectFactory = objectFactory;
        this.bufferPool = bufferPool;
    }

    private DecoratedObjectFactory objectFactory;
    private WebSocketExtensionRegistry extensionRegistry;
    private ByteBufferPool bufferPool;


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
}
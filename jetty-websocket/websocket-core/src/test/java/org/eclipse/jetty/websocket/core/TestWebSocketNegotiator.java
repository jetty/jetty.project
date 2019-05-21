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

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class TestWebSocketNegotiator implements WebSocketNegotiator
{
    final DecoratedObjectFactory objectFactory;
    final WebSocketExtensionRegistry extensionRegistry;
    final ByteBufferPool bufferPool;
    private final FrameHandler frameHandler;

    public TestWebSocketNegotiator(FrameHandler frameHandler)
    {
        this.objectFactory = new DecoratedObjectFactory();
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.bufferPool = new MappedByteBufferPool();
        this.frameHandler = frameHandler;
    }

    public TestWebSocketNegotiator(DecoratedObjectFactory objectFactory, WebSocketExtensionRegistry extensionRegistry, ByteBufferPool bufferPool,
                                   FrameHandler frameHandler)
    {
        this.objectFactory = objectFactory;
        this.extensionRegistry = extensionRegistry;
        this.bufferPool = bufferPool;
        this.frameHandler = frameHandler;
    }

    @Override
    public FrameHandler negotiate(Negotiation negotiation) throws IOException
    {
        List<String> offeredSubprotocols = negotiation.getOfferedSubprotocols();
        if (!offeredSubprotocols.isEmpty())
            negotiation.setSubprotocol(offeredSubprotocols.get(0));

        return frameHandler;
    }

    @Override
    public void customize(FrameHandler.Configuration session)
    {
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
    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }
}

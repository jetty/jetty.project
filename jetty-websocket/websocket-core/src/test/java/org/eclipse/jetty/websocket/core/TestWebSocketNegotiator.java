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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

import java.io.IOException;
import java.util.List;

public class TestWebSocketNegotiator implements WebSocketNegotiator
{
    final DecoratedObjectFactory objectFactory;
    final WebSocketExtensionRegistry extensionRegistry;
    final ByteBufferPool bufferPool;
    private final FrameHandler frameHandler;

    public TestWebSocketNegotiator(DecoratedObjectFactory objectFactory, WebSocketExtensionRegistry extensionRegistry, ByteBufferPool bufferPool, FrameHandler frameHandler)
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
        if (!offeredSubprotocols.contains("test"))
            return null;
        negotiation.setSubprotocol("test");
        negotiation.getResponse().addHeader(HttpHeader.SEC_WEBSOCKET_EXTENSIONS.asString(),"@validation; outgoing-sequence; incoming-sequence; outgoing-frame; incoming-frame; incoming-utf8; outgoing-utf8");
        return frameHandler;
    }

    @Override
    public void customize(FrameHandler.CoreSession session)
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

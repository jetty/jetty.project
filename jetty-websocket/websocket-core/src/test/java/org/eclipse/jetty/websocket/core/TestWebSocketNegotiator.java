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

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class TestWebSocketNegotiator implements WebSocketNegotiator
{
    final WebSocketComponents components;
    private final FrameHandler frameHandler;

    public TestWebSocketNegotiator(FrameHandler frameHandler)
    {
        this (frameHandler, new WebSocketComponents());
    }

    public TestWebSocketNegotiator(FrameHandler frameHandler, WebSocketComponents components)
    {
        this.components = components;
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
    public void customize(Configuration configurable)
    {
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return components.getExtensionRegistry();
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return components.getObjectFactory();
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return components.getBufferPool();
    }

    @Override
    public WebSocketComponents getWebSocketComponents()
    {
        return components;
    }
}

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

package org.eclipse.jetty.websocket.core.chat;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ChatWebSocketNegotiator implements WebSocketNegotiator
{
    final DecoratedObjectFactory objectFactory;
    final WebSocketExtensionRegistry extensionRegistry;
    final ByteBufferPool bufferPool;

    Set<FrameHandler.CoreSession> channelSet = Collections.synchronizedSet(new HashSet());

    public ChatWebSocketNegotiator(DecoratedObjectFactory objectFactory, WebSocketExtensionRegistry extensionRegistry, ByteBufferPool bufferPool)
    {
        this.objectFactory = objectFactory;
        this.extensionRegistry = extensionRegistry;
        this.bufferPool = bufferPool;
    }

    @Override
    public FrameHandler negotiate(Negotiation negotiation) throws IOException
    {
        // Finalize negotiations in API layer involves:
        // TODO need access to real request/response????
        //  + MAY mutate the policy
        //  + MAY replace the policy
        //  + MAY read request and set response headers
        //  + MAY reject with sendError semantics
        //  + MAY change/add/remove offered extensions 
        //  + MUST pick subprotocol
        List<String> subprotocols = negotiation.getOfferedSubprotocols();
        if (!subprotocols.contains("chat"))
            return null;        
        negotiation.setSubprotocol("chat");
        //  + MUST return the FrameHandler or null or exception?
        return new ChatWebSocketServer(channelSet);
    }

    @Override
    public WebSocketPolicy getCandidatePolicy()
    {
        return null;
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

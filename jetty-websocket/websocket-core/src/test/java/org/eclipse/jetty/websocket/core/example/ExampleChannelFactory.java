//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.example;

import java.util.List;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.WebSocketChannel;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.WebSocketChannelFactory;

class ExampleChannelFactory implements WebSocketChannelFactory
{
    DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
    WebSocketExtensionRegistry extensionRegistry = new WebSocketExtensionRegistry();

    @Override
    public WebSocketChannel newChannel(
            Request baseRequest,
            ServletRequest request, 
            WebSocketPolicy candidatePolicy,                               
            ByteBufferPool bufferPool,
            List<ExtensionConfig> extensions, 
            List<String> subprotocols)
    {
        ExtensionStack extensionStack = new ExtensionStack(extensionRegistry);
        extensionStack.negotiate(objectFactory, candidatePolicy, bufferPool, extensions);

        FrameHandler handler = new ExampleFrameHandler();
        String subprotocol = (subprotocols==null || subprotocols.isEmpty())?null:subprotocols.get(0);
        
        return new WebSocketChannel(handler,candidatePolicy,extensionStack,subprotocol);
    }

}

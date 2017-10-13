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
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.example.impl.WebSocketSessionFactory;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;

class ExampleWebSocketSessionFactory implements WebSocketSessionFactory
{
    DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();

    @Override
    public WebSocketCoreSession newSession(Request baseRequest, ServletRequest request, WebSocketPolicy policy,
                                           ByteBufferPool bufferPool, List<ExtensionConfig> extensions, List<String> subprotocols)
    {
        ExtensionStack extensionStack = new ExtensionStack(policy.getExtensionRegistry());
        extensionStack.negotiate(objectFactory, policy, bufferPool, extensions);

        ExampleLocalEndpoint localEndpoint = new ExampleLocalEndpoint();
        ExampleRemoteEndpoint remoteEndpoint = new ExampleRemoteEndpoint(extensionStack);

        WebSocketCoreSession session =
                new WebSocketCoreSession(localEndpoint,remoteEndpoint,policy,extensionStack);

        localEndpoint.setSession(session);
        return session;
    }

}

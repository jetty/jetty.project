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

package org.eclipse.jetty.websocket.common.test;

import java.net.URI;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class BlockheadClient extends HttpClient implements WebSocketContainerScope
{
    private WebSocketPolicy policy;
    private ByteBufferPool bufferPool;
    private ExtensionFactory extensionFactory;
    private DecoratedObjectFactory objectFactory;

    public BlockheadClient()
    {
        super(null);
        setName("Blockhead-CLIENT");
        this.policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        this.bufferPool = new MappedByteBufferPool();
        this.extensionFactory = new WebSocketExtensionFactory(this);
        this.objectFactory = new DecoratedObjectFactory();
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return objectFactory;
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public BlockheadClientRequest newWsRequest(URI destURI)
    {
        return new BlockheadClientRequest(this, destURI);
    }

    @Override
    public void onSessionOpened(WebSocketSession session)
    { /* ignored */ }

    @Override
    public void onSessionClosed(WebSocketSession session)
    { /* ignored */ }
}

//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class BlockheadClient extends HttpClient implements WebSocketContainerScope
{
    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
    private ByteBufferPool bufferPool = new MappedByteBufferPool();
    private ExtensionFactory extensionFactory;
    private DecoratedObjectFactory objectFactory = new DecoratedObjectFactory();
    private List<WebSocketSessionListener> listeners = new ArrayList<>();

    public BlockheadClient()
    {
        setName("Blockhead-CLIENT");
        this.extensionFactory = new WebSocketExtensionFactory(this);
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

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        listeners.add(listener);
    }

    @Override
    public void removeSessionListener(WebSocketSessionListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public Collection<WebSocketSessionListener> getSessionListeners()
    {
        return listeners;
    }

    public BlockheadClientRequest newWsRequest(URI destURI)
    {
        return new BlockheadClientRequest(this, destURI);
    }
}

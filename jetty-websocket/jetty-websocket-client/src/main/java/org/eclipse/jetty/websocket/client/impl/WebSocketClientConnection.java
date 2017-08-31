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

package org.eclipse.jetty.websocket.client.impl;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.WSConnection;

/**
 * Client side WebSocket physical connection.
 */
public class WebSocketClientConnection extends WSConnection
{
    public WebSocketClientConnection(EndPoint endp, Executor executor, ByteBufferPool bufferPool,
                                     DecoratedObjectFactory decoratedObjectFactory,
                                     WSPolicy policy, ExtensionStack extensionStack,
                                     UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse)
    {
        super(endp,executor, bufferPool, decoratedObjectFactory, policy, extensionStack, upgradeRequest, upgradeResponse);
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return getEndPoint().getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return getEndPoint().getRemoteAddress();
    }
    
    /**
     * Override to set the mask.
     */
    @Override
    public void outgoingFrame(org.eclipse.jetty.websocket.core.Frame frame, Callback callback, org.eclipse.jetty.websocket.core.io.BatchMode batchMode)
    {
        if (frame instanceof WSFrame)
        {
            WSFrame wsFrame = (WSFrame) frame;
            byte mask[] = new byte[4];
            ThreadLocalRandom.current().nextBytes(mask);
            wsFrame.setMask(mask);
        }
        super.outgoingFrame(frame,callback, batchMode);
    }
}

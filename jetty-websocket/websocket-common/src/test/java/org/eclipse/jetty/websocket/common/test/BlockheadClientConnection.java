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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;

public class BlockheadClientConnection extends BlockheadConnection
{
    public BlockheadClientConnection(WebSocketPolicy policy,
                                     ByteBufferPool bufferPool,
                                     ExtensionStack extensionStack,
                                     CompletableFuture<BlockheadConnection> openFut,
                                     EndPoint endp,
                                     Executor executor)
    {
        super(policy, bufferPool, extensionStack, openFut, endp, executor);
    }

    @Override
    public void write(WebSocketFrame frame)
    {
        if (frame.getMask() == null)
        {
            byte[] mask = new byte[4];
            ThreadLocalRandom.current().nextBytes(mask);
            frame.setMask(mask);
        }
        super.write(frame);
    }
}

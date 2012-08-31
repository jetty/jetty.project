//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import java.util.concurrent.Executor;


import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.io.AbstractWebSocketConnection;

public class WebSocketServerConnection extends AbstractWebSocketConnection
{
    private final WebSocketServerFactory factory;
    private boolean connected;

    public WebSocketServerConnection(EndPoint endp, Executor executor, Scheduler scheduler, WebSocketPolicy policy,
            ByteBufferPool bufferPool, WebSocketServerFactory factory)
    {
        super(endp,executor,scheduler,policy,bufferPool);
        this.factory = factory;
        this.connected = false;
    }

    @Override
    public void onClose()
    {
        super.onClose();
        factory.sessionClosed(getSession());
    }

    @Override
    public void onOpen()
    {
        if (!connected)
        {
            factory.sessionOpened(getSession());
            connected = true;
        }
        super.onOpen();
    }
}

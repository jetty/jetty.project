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

package org.eclipse.jetty.websocket.client.internal.io;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.client.internal.IWebSocketClient;
import org.eclipse.jetty.websocket.io.AbstractWebSocketConnection;

public class WebSocketClientConnection extends AbstractWebSocketConnection
{
    private final IWebSocketClient client;

    public WebSocketClientConnection(EndPoint endp, Executor executor, IWebSocketClient client)
    {
        super(endp,executor,client.getFactory().getScheduler(),client.getPolicy(),client.getFactory().getBufferPool());
        this.client = client;
    }

    public IWebSocketClient getClient()
    {
        return client;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
    }
}

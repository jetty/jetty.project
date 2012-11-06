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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import javax.net.websocket.SendResult;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.client.internal.DefaultWebSocketClient;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.common.io.IncomingFrames;

/**
 * Client side WebSocket physical connection.
 */
public class WebSocketClientConnection extends AbstractWebSocketConnection
{
    private final WebSocketClientFactory factory;
    private final DefaultWebSocketClient client;
    private final Masker masker;
    private boolean connected;

    public WebSocketClientConnection(EndPoint endp, Executor executor, DefaultWebSocketClient client)
    {
        super(endp,executor,client.getFactory().getScheduler(),client.getPolicy(),client.getFactory().getBufferPool());
        this.client = client;
        this.factory = client.getFactory();
        this.connected = false;
        this.masker = client.getMasker();
    }

    @Override
    public void configureFromExtensions(List<Extension> extensions)
    {
        /* do nothing */
    }

    public DefaultWebSocketClient getClient()
    {
        return client;
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

    @Override
    public Future<SendResult> outgoingFrame(WebSocketFrame frame) throws IOException
    {
        masker.setMask(frame);
        return super.outgoingFrame(frame);
    }

    @Override
    public void setIncoming(IncomingFrames incoming)
    {
        getParser().setIncomingFramesHandler(incoming);
    }
}

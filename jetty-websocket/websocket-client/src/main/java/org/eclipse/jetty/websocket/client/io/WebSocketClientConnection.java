//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.io;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;

/**
 * Client side WebSocket physical connection.
 */
public class WebSocketClientConnection extends AbstractWebSocketConnection
{
    private static final Logger LOG = Log.getLogger(WebSocketClientConnection.class);
    private final ConnectPromise connectPromise;
    private final Masker masker;
    private final AtomicBoolean opened = new AtomicBoolean(false);

    public WebSocketClientConnection(EndPoint endp, Executor executor, ConnectPromise connectPromise, WebSocketPolicy policy)
    {
        super(endp,executor,connectPromise.getClient().getScheduler(),policy,connectPromise.getClient().getBufferPool());
        this.connectPromise = connectPromise;
        this.masker = connectPromise.getMasker();
        assert (this.masker != null);
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

    @Override
    public void onClose()
    {
        super.onClose();
        ConnectionManager connectionManager = connectPromise.getClient().getConnectionManager();
        connectionManager.removeSession(getSession());
    }

    @Override
    public void onOpen()
    {
        boolean beenOpened = opened.getAndSet(true);
        if (!beenOpened)
        {
            WebSocketSession session = getSession();
            ConnectionManager connectionManager = connectPromise.getClient().getConnectionManager();
            connectionManager.addSession(session);
            connectPromise.succeeded(session);

            ByteBuffer extraBuf = connectPromise.getResponse().getRemainingBuffer();
            if (extraBuf.hasRemaining())
            {
                LOG.debug("Parsing extra remaining buffer from UpgradeConnection");
                getParser().parse(extraBuf);
            }
        }
        super.onOpen();
    }

    /**
     * Override to set the masker.
     */
    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        if (frame instanceof WebSocketFrame)
        {
            masker.setMask((WebSocketFrame)frame);
        }
        super.outgoingFrame(frame,callback, batchMode);
    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        getParser().setIncomingFramesHandler(incoming);
    }
}

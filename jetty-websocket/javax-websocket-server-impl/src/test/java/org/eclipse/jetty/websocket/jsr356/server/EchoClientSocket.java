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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.EncodeException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;

@ClientEndpoint
public class EchoClientSocket extends TrackingSocket
{
    private Session session;
    private Basic remote;

    public void close() throws IOException
    {
        if (session != null)
        {
            this.session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Test Complete"));
        }
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        this.session = null;
        super.closeReason = close;
        super.closeLatch.countDown();
    }

    @OnError
    public void onError(Throwable t)
    {
        if (t == null)
        {
            addError(new NullPointerException("Throwable should not be null"));
        }
        else
        {
            addError(t);
        }
    }

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
        this.remote = session.getBasicRemote();
        openLatch.countDown();
    }

    @OnMessage
    public void onText(String text)
    {
        addEvent(text);
    }

    public void sendObject(Object obj) throws IOException, EncodeException
    {
        remote.sendObject(obj);
    }

    public void sendPartialBinary(ByteBuffer part, boolean fin) throws IOException
    {
        remote.sendBinary(part, fin);
    }

    public void sendPartialText(String part, boolean fin) throws IOException
    {
        remote.sendText(part, fin);
    }

    public void sendPing(String message) throws IOException
    {
        remote.sendPing(BufferUtil.toBuffer(message));
    }

    public void sendPong(String message) throws IOException
    {
        remote.sendPong(BufferUtil.toBuffer(message));
    }
}

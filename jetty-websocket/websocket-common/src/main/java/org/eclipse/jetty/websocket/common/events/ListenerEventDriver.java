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

package org.eclipse.jetty.websocket.common.events;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.message.MessageAppender;
import org.eclipse.jetty.websocket.common.message.SimpleBinaryMessage;
import org.eclipse.jetty.websocket.common.message.SimpleTextMessage;

/**
 * Handler for {@link WebSocketListener} based User WebSocket implementations.
 */
public class ListenerEventDriver extends EventDriver
{
    private final WebSocketListener listener;
    private MessageAppender activeMessage;

    public ListenerEventDriver(WebSocketPolicy policy, WebSocketListener listener)
    {
        super(policy,listener);
        this.listener = listener;
    }

    @Override
    public void onBinaryFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            activeMessage = new SimpleBinaryMessage(this);
        }

        activeMessage.appendMessage(buffer);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    @Override
    public void onBinaryMessage(byte[] data)
    {
        listener.onWebSocketBinary(data,0,data.length);
    }

    @Override
    public void onClose(CloseInfo close)
    {
        int statusCode = close.getStatusCode();
        String reason = close.getReason();
        listener.onWebSocketClose(statusCode,reason);
    }

    @Override
    public void onConnect()
    {
        LOG.debug("onConnect()");
        listener.onWebSocketConnect(session);
    }

    @Override
    public void onException(WebSocketException e)
    {
        listener.onWebSocketException(e);
    }

    @Override
    public void onFrame(Frame frame)
    {
        /* ignore, not supported by WebSocketListener */
    }

    @Override
    public void onTextFrame(ByteBuffer buffer, boolean fin) throws IOException
    {
        if (activeMessage == null)
        {
            activeMessage = new SimpleTextMessage(this);
        }

        activeMessage.appendMessage(buffer);

        if (fin)
        {
            activeMessage.messageComplete();
            activeMessage = null;
        }
    }

    @Override
    public void onTextMessage(String message)
    {
        listener.onWebSocketText(message);
    }
}

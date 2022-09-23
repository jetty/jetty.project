//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.common;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;

import jakarta.websocket.EncodeException;
import jakarta.websocket.RemoteEndpoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

public class JakartaWebSocketBasicRemote extends JakartaWebSocketRemoteEndpoint implements RemoteEndpoint.Basic
{
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketBasicRemote.class);

    protected JakartaWebSocketBasicRemote(JakartaWebSocketSession session, CoreSession coreSession)
    {
        super(session, coreSession);
    }

    @Override
    public OutputStream getSendStream() throws IOException
    {
        return newMessageOutputStream();
    }

    @Override
    public Writer getSendWriter() throws IOException
    {
        return newMessageWriter();
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException
    {
        assertMessageNotNull(data);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({})", BufferUtil.toDetailString(data));
        }

        FutureCallback b = new FutureCallback();
        sendFrame(new Frame(OpCode.BINARY).setPayload(data), b, false);
        b.block();
    }

    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException
    {
        assertMessageNotNull(partialByte);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({},{})", BufferUtil.toDetailString(partialByte), isLast);
        }

        Frame frame;
        switch (messageType)
        {
            case -1:
                // New message!
                frame = new Frame(OpCode.BINARY);
                break;
            case OpCode.TEXT:
                throw new IllegalStateException("Cannot send a partial BINARY message: TEXT message in progress");
            case OpCode.BINARY:
                frame = new Frame(OpCode.CONTINUATION);
                break;
            default:
                throw new IllegalStateException("Cannot send a partial BINARY message: unrecognized active message type " + OpCode.name(messageType));
        }

        frame.setPayload(partialByte);
        frame.setFin(isLast);
        FutureCallback b = new FutureCallback();
        sendFrame(frame, b, false);
        b.block();
    }

    @Override
    public void sendObject(Object data) throws IOException, EncodeException
    {
        FutureCallback b = new FutureCallback();
        super.sendObject(data, b);
        b.block();
    }

    @Override
    public void sendText(String text) throws IOException
    {
        assertMessageNotNull(text);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({})", TextUtils.hint(text));
        }

        FutureCallback b = new FutureCallback();
        sendFrame(new Frame(OpCode.TEXT).setPayload(text), b, false);
        b.block();
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException
    {
        assertMessageNotNull(partialMessage);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({},{})", TextUtils.hint(partialMessage), isLast);
        }

        Frame frame;
        switch (messageType)
        {
            case -1:
                // New message!
                frame = new Frame(OpCode.TEXT);
                break;
            case OpCode.TEXT:
                frame = new Frame(OpCode.CONTINUATION);
                break;
            case OpCode.BINARY:
                throw new IllegalStateException("Cannot send a partial TEXT message: BINARY message in progress");
            default:
                throw new IllegalStateException("Cannot send a partial TEXT message: unrecognized active message type " + OpCode.name(messageType));
        }

        frame.setPayload(BufferUtil.toBuffer(partialMessage, UTF_8));
        frame.setFin(isLast);
        FutureCallback b = new FutureCallback();
        sendFrame(frame, b, false);
        b.block();
    }
}

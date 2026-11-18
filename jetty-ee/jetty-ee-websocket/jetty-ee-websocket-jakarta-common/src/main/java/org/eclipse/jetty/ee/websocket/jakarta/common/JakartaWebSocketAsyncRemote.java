//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.websocket.jakarta.common;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import jakarta.websocket.Encoder;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.messages.MessageOutputStream;
import org.eclipse.jetty.websocket.core.messages.MessageWriter;
import org.eclipse.jetty.websocket.core.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaWebSocketAsyncRemote extends JakartaWebSocketRemoteEndpoint implements jakarta.websocket.RemoteEndpoint.Async
{
    static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketAsyncRemote.class);

    protected JakartaWebSocketAsyncRemote(JakartaWebSocketSession session, CoreSession coreSession)
    {
        super(session, coreSession);
        messageWriteTimeout = session.getContainer().getDefaultAsyncSendTimeout();
    }

    @Override
    public long getSendTimeout()
    {
        return messageWriteTimeout;
    }

    @Override
    public void setSendTimeout(long timeoutMillis)
    {
        messageWriteTimeout = timeoutMillis;
    }

    @Override
    public Future<Void> sendBinary(ByteBuffer data)
    {
        assertMessageNotNull(data);
        if (LOG.isDebugEnabled())
            LOG.debug("sendBinary({})", BufferUtil.toDetailString(data));
        try (Frame frame = new Frame(OpCode.BINARY, RetainableByteBuffer.wrap(data)))
        {
            FutureCallback future = new FutureCallback();
            sendFrame(frame, future, batch);
            return future;
        }
    }

    @Override
    public void sendBinary(ByteBuffer data, jakarta.websocket.SendHandler handler)
    {
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
            LOG.debug("sendBinary({},{})", BufferUtil.toDetailString(data), handler);
        try (Frame frame = new Frame(OpCode.BINARY, RetainableByteBuffer.wrap(data)))
        {
            sendFrame(frame, new SendHandlerCallback(handler), batch);
        }
    }

    @Override
    public Future<Void> sendObject(Object data)
    {
        FutureCallback future = new FutureCallback();
        try
        {
            sendObject(data, future);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            future.failed(t);
        }
        return future;
    }

    @Override
    public void sendObject(Object data, SendHandler handler)
    {
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
            LOG.debug("sendObject({},{})", data, handler);

        Encoder encoder = session.getEncoders().getInstanceFor(data.getClass());
        switch (encoder)
        {
            case null -> throw new IllegalArgumentException("No encoder for type: " + data.getClass());
            case Encoder.Text _ ->
            {
                @SuppressWarnings("unchecked")
                Encoder.Text<Object> text = (Encoder.Text<Object>)encoder;
                try
                {
                    String msg = text.encode(data);
                    sendText(msg, handler);
                }
                catch (Throwable t)
                {
                    handler.onResult(new SendResult(t));
                }
            }
            case Encoder.TextStream _ ->
            {
                @SuppressWarnings("unchecked")
                Encoder.TextStream<Object> text = (Encoder.TextStream<Object>)encoder;
                try
                {
                    MessageWriter writer = newMessageWriter();
                    writer.setCallback(new SendHandlerCallback(handler));
                    text.encode(data, writer);
                    writer.close();
                }
                catch (Throwable t)
                {
                    handler.onResult(new SendResult(t));
                }
            }
            case Encoder.Binary _ ->
            {
                @SuppressWarnings("unchecked")
                Encoder.Binary<Object> bin = (Encoder.Binary<Object>)encoder;
                try
                {
                    ByteBuffer buf = bin.encode(data);
                    sendBinary(buf, handler);
                }
                catch (Throwable t)
                {
                    handler.onResult(new SendResult(t));
                }
            }
            case Encoder.BinaryStream _ ->
            {
                @SuppressWarnings("unchecked")
                Encoder.BinaryStream<Object> bin = (Encoder.BinaryStream<Object>)encoder;
                try
                {
                    MessageOutputStream out = newMessageOutputStream();
                    out.setCallback(new SendHandlerCallback(handler));
                    bin.encode(data, out);
                    out.close();
                }
                catch (Throwable t)
                {
                    handler.onResult(new SendResult(t));
                }
            }
            default -> throw new IllegalArgumentException("Unknown encoder type: " + encoder);
        }
    }

    @Override
    public Future<Void> sendText(String text)
    {
        assertMessageNotNull(text);
        if (LOG.isDebugEnabled())
            LOG.debug("sendText({})", TextUtils.hint(text));
        try (Frame frame = new Frame(OpCode.TEXT, text))
        {
            FutureCallback future = new FutureCallback();
            sendFrame(frame, future, batch);
            return future;
        }
    }

    @Override
    public void sendText(String text, SendHandler handler)
    {
        assertMessageNotNull(text);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
            LOG.debug("sendText({},{})", TextUtils.hint(text), handler);
        try (Frame frame = new Frame(OpCode.TEXT, text))
        {
            sendFrame(frame, new SendHandlerCallback(handler), batch);
        }
    }
}

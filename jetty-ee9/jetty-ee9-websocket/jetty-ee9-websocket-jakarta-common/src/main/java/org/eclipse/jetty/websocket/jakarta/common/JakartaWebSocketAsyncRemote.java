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
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.messages.MessageOutputStream;
import org.eclipse.jetty.websocket.core.internal.messages.MessageWriter;
import org.eclipse.jetty.websocket.core.internal.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaWebSocketAsyncRemote extends JakartaWebSocketRemoteEndpoint implements jakarta.websocket.RemoteEndpoint.Async
{
    static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketAsyncRemote.class);

    protected JakartaWebSocketAsyncRemote(JakartaWebSocketSession session, CoreSession coreSession)
    {
        super(session, coreSession);
    }

    @Override
    public long getSendTimeout()
    {
        return getWriteTimeout();
    }

    @Override
    public void setSendTimeout(long timeoutmillis)
    {
        setWriteTimeout(timeoutmillis);
    }

    @Override
    public Future<Void> sendBinary(ByteBuffer data)
    {
        assertMessageNotNull(data);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({})", BufferUtil.toDetailString(data));
        }
        FutureCallback future = new FutureCallback();
        sendFrame(new Frame(OpCode.BINARY).setPayload(data), future, batch);
        return future;
    }

    @Override
    public void sendBinary(ByteBuffer data, jakarta.websocket.SendHandler handler)
    {
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({},{})", BufferUtil.toDetailString(data), handler);
        }
        sendFrame(new Frame(OpCode.BINARY).setPayload(data), new SendHandlerCallback(handler), batch);
    }

    @Override
    public Future<Void> sendObject(Object data)
    {
        FutureCallback future = new FutureCallback();
        try
        {
            sendObject(data, future);
        }
        catch (Throwable t)
        {
            future.failed(t);
        }
        return future;
    }

    @SuppressWarnings(
        {"rawtypes", "unchecked"})
    @Override
    public void sendObject(Object data, SendHandler handler)
    {
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendObject({},{})", data, handler);
        }

        Encoder encoder = session.getEncoders().getInstanceFor(data.getClass());
        if (encoder == null)
        {
            throw new IllegalArgumentException("No encoder for type: " + data.getClass());
        }

        if (encoder instanceof Encoder.Text)
        {
            Encoder.Text etxt = (Encoder.Text)encoder;
            try
            {
                String msg = etxt.encode(data);
                sendText(msg, handler);
                return;
            }
            catch (EncodeException e)
            {
                handler.onResult(new SendResult(e));
            }
        }
        else if (encoder instanceof Encoder.TextStream)
        {
            Encoder.TextStream etxt = (Encoder.TextStream)encoder;
            SendHandlerCallback callback = new SendHandlerCallback(handler);
            try (MessageWriter writer = newMessageWriter())
            {
                writer.setCallback(callback);
                etxt.encode(data, writer);
                return;
            }
            catch (EncodeException | IOException e)
            {
                handler.onResult(new SendResult(e));
            }
        }
        else if (encoder instanceof Encoder.Binary)
        {
            Encoder.Binary ebin = (Encoder.Binary)encoder;
            try
            {
                ByteBuffer buf = ebin.encode(data);
                sendBinary(buf, handler);
                return;
            }
            catch (EncodeException e)
            {
                handler.onResult(new SendResult(e));
            }
        }
        else if (encoder instanceof Encoder.BinaryStream)
        {
            Encoder.BinaryStream ebin = (Encoder.BinaryStream)encoder;
            SendHandlerCallback callback = new SendHandlerCallback(handler);
            try (MessageOutputStream out = newMessageOutputStream())
            {
                out.setCallback(callback);
                ebin.encode(data, out);
                return;
            }
            catch (EncodeException | IOException e)
            {
                handler.onResult(new SendResult(e));
            }
        }

        throw new IllegalArgumentException("Unknown encoder type: " + encoder);
    }

    @Override
    public Future<Void> sendText(String text)
    {
        assertMessageNotNull(text);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({})", TextUtils.hint(text));
        }
        FutureCallback future = new FutureCallback();
        sendFrame(new Frame(OpCode.TEXT).setPayload(text), future, batch);
        return future;
    }

    @Override
    public void sendText(String text, SendHandler handler)
    {
        assertMessageNotNull(text);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({},{})", TextUtils.hint(text), handler);
        }
        sendFrame(new Frame(OpCode.TEXT).setPayload(text), new SendHandlerCallback(handler), batch);
    }
}

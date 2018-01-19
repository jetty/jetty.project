//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.messages.MessageOutputStream;
import org.eclipse.jetty.websocket.jsr356.messages.MessageWriter;
import org.eclipse.jetty.websocket.jsr356.util.TextUtil;

public class JavaxWebSocketAsyncRemote extends JavaxWebSocketRemoteEndpoint implements javax.websocket.RemoteEndpoint.Async
{
    static final Logger LOG = Log.getLogger(JavaxWebSocketAsyncRemote.class);

    protected JavaxWebSocketAsyncRemote(JavaxWebSocketSession session, FrameHandler.Channel channel)
    {
        super(session, channel);
    }

    @Override
    public long getSendTimeout()
    {
        // TODO: verify that JSR356 "send timeout" means the same as connection idle timeout
        return getIdleTimeout();
    }

    @Override
    public void setSendTimeout(long timeoutmillis)
    {
        // TODO: verify that JSR356 "send timeout" means the same as connection idle timeout
        setIdleTimeout(timeoutmillis);
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
        sendFrame(new BinaryFrame().setPayload(data), future, batchMode);
        return future;
    }

    @Override
    public void sendBinary(ByteBuffer data, javax.websocket.SendHandler handler)
    {
        assertMessageNotNull(data);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBinary({},{})", BufferUtil.toDetailString(data), handler);
        }
        sendFrame(new BinaryFrame().setPayload(data), new SendHandlerCallback(handler), batchMode);
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
            Encoder.Text etxt = (Encoder.Text) encoder;
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
            Encoder.TextStream etxt = (Encoder.TextStream) encoder;
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
            Encoder.Binary ebin = (Encoder.Binary) encoder;
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
            Encoder.BinaryStream ebin = (Encoder.BinaryStream) encoder;
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
            LOG.debug("sendText({})", TextUtil.hint(text));
        }
        FutureCallback future = new FutureCallback();
        sendFrame(new TextFrame().setPayload(text), future, batchMode);
        return future;
    }

    @Override
    public void sendText(String text, SendHandler handler)
    {
        assertMessageNotNull(text);
        assertSendHandlerNotNull(handler);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendText({},{})", TextUtil.hint(text), handler);
        }
        sendFrame(new TextFrame().setPayload(text), new SendHandlerCallback(handler), batchMode);
    }
}

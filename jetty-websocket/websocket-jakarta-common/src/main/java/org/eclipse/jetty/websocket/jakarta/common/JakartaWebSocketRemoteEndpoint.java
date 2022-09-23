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
import java.time.Duration;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.SendHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.messages.MessageOutputStream;
import org.eclipse.jetty.websocket.core.internal.messages.MessageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaWebSocketRemoteEndpoint implements jakarta.websocket.RemoteEndpoint, OutgoingFrames
{
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketRemoteEndpoint.class);
    protected final JakartaWebSocketSession session;
    private final CoreSession coreSession;
    protected boolean batch = false;
    protected byte messageType = -1;

    protected JakartaWebSocketRemoteEndpoint(JakartaWebSocketSession session, CoreSession coreSession)
    {
        this.session = session;
        this.coreSession = coreSession;
    }

    protected MessageWriter newMessageWriter()
    {
        return new MessageWriter(coreSession, session.getContainerImpl().getBufferPool());
    }

    protected MessageOutputStream newMessageOutputStream()
    {
        return new MessageOutputStream(coreSession, session.getContainerImpl().getBufferPool());
    }

    @Override
    public void flushBatch() throws IOException
    {
        FutureCallback b = new FutureCallback();
        coreSession.flush(b);
        b.block();
    }

    @Override
    public boolean getBatchingAllowed()
    {
        return batch;
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException
    {
        if (batch && !allowed)
            flushBatch();
        batch = allowed;
    }

    public long getIdleTimeout()
    {
        return coreSession.getIdleTimeout().toMillis();
    }

    public void setIdleTimeout(long ms)
    {
        coreSession.setIdleTimeout(Duration.ofMillis(ms));
    }

    public long getWriteTimeout()
    {
        return coreSession.getWriteTimeout().toMillis();
    }

    public void setWriteTimeout(long ms)
    {
        coreSession.setWriteTimeout(Duration.ofMillis(ms));
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        if (frame.isDataFrame())
        {
            try
            {
                byte opcode = frame.getOpCode();

                if (messageType == -1) // new message
                {
                    if ((opcode == OpCode.BINARY) || (opcode == OpCode.TEXT))
                    {
                        messageType = opcode;
                    }
                    else
                    {
                        throw new WebSocketException("Encountered invalid Data Frame opcode " + opcode);
                    }
                }
                else if ((messageType == OpCode.BINARY) && (opcode == OpCode.TEXT))
                {
                    throw new WebSocketException("Cannot start TEXT message when BINARY message is not complete yet");
                }
                else if ((messageType == OpCode.TEXT) && (opcode == OpCode.BINARY))
                {
                    throw new WebSocketException("Cannot start BINARY message when TEXT message is not complete yet");
                }
            }
            catch (Throwable t)
            {
                callback.failed(t);
                return;
            }
        }

        try
        {
            coreSession.sendFrame(frame, callback, batch);
        }
        finally
        {
            if (frame.isFin())
            {
                messageType = -1;
            }
        }
    }

    public void sendObject(Object data, Callback callback) throws IOException, EncodeException
    {
        try
        {
            assertMessageNotNull(data);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendObject({}, {})", data, callback);
            }

            Encoder encoder = session.getEncoders().getInstanceFor(data.getClass());
            if (encoder == null)
            {
                throw new IllegalArgumentException("No encoder for type: " + data.getClass());
            }

            if (encoder instanceof Encoder.Text)
            {
                Encoder.Text text = (Encoder.Text)encoder;
                String msg = text.encode(data);
                sendFrame(new Frame(OpCode.TEXT).setPayload(msg), callback, batch);
                return;
            }

            if (encoder instanceof Encoder.TextStream)
            {
                Encoder.TextStream etxt = (Encoder.TextStream)encoder;
                try (MessageWriter writer = newMessageWriter())
                {
                    writer.setCallback(callback);
                    etxt.encode(data, writer);
                }
                return;
            }

            if (encoder instanceof Encoder.Binary)
            {
                Encoder.Binary ebin = (Encoder.Binary)encoder;
                ByteBuffer buf = ebin.encode(data);
                sendFrame(new Frame(OpCode.BINARY).setPayload(buf), callback, batch);
                return;
            }

            if (encoder instanceof Encoder.BinaryStream)
            {
                Encoder.BinaryStream ebin = (Encoder.BinaryStream)encoder;
                try (MessageOutputStream out = newMessageOutputStream())
                {
                    out.setCallback(callback);
                    ebin.encode(data, out);
                }
                return;
            }

            throw new IllegalArgumentException("Unknown encoder type: " + encoder);
        }
        catch (RuntimeException | IOException | EncodeException e)
        {
            callback.failed(e);
            throw e;
        }
        catch (Throwable t)
        {
            callback.failed(t);
            LOG.warn("Unable to send Object {}", data, t);
        }
    }

    @Override
    public void sendPing(ByteBuffer data) throws IOException, IllegalArgumentException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendPing({})", BufferUtil.toDetailString(data));

        FutureCallback b = new FutureCallback();
        sendFrame(new Frame(OpCode.PING).setPayload(data), b, batch);
        b.block();
    }

    @Override
    public void sendPong(ByteBuffer data) throws IOException, IllegalArgumentException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendPong({})", BufferUtil.toDetailString(data));

        FutureCallback b = new FutureCallback();
        sendFrame(new Frame(OpCode.PONG).setPayload(data), b, batch);
        b.block();
    }

    protected void assertMessageNotNull(Object data)
    {
        if (data == null)
        {
            throw new IllegalArgumentException("message cannot be null");
        }
    }

    protected void assertSendHandlerNotNull(SendHandler handler)
    {
        if (handler == null)
        {
            throw new IllegalArgumentException("SendHandler cannot be null");
        }
    }
}

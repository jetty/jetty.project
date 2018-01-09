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

import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.SendHandler;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.eclipse.jetty.websocket.core.io.WebSocketRemoteEndpointImpl;
import org.eclipse.jetty.websocket.jsr356.messages.MessageOutputStream;
import org.eclipse.jetty.websocket.jsr356.messages.MessageWriter;

public class JavaxWebSocketRemoteEndpoint extends WebSocketRemoteEndpointImpl implements javax.websocket.RemoteEndpoint
{
    private static final Logger LOG = Log.getLogger(JavaxWebSocketRemoteEndpoint.class);

    protected final JavaxWebSocketSession session;

    public JavaxWebSocketRemoteEndpoint(JavaxWebSocketSession session)
    {
        super(session.getConnection());
        this.session = session;

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

    @Override
    public void flushBatch() throws IOException
    {
        super.flush();
    }

    @Override
    public boolean getBatchingAllowed()
    {
        return getBatchMode() == BatchMode.ON;
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException
    {
        if (getBatchMode() == BatchMode.ON && !allowed)
        {
            super.flush();
        }
        setBatchMode(allowed ? BatchMode.ON : BatchMode.OFF);
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
                Encoder.Text text = (Encoder.Text) encoder;
                String msg = text.encode(data);
                sendText(msg, callback);
                return;
            }

            if (encoder instanceof Encoder.TextStream)
            {
                Encoder.TextStream etxt = (Encoder.TextStream) encoder;
                WebSocketCoreConnection connection = session.getConnection();
                try (MessageWriter writer = new MessageWriter(connection, connection.getInputBufferSize(), connection.getBufferPool()))
                {
                    writer.setCallback(callback);
                    etxt.encode(data, writer);
                }
                return;
            }

            if (encoder instanceof Encoder.Binary)
            {
                Encoder.Binary ebin = (Encoder.Binary) encoder;
                ByteBuffer buf = ebin.encode(data);
                sendBinary(buf, callback);
                return;
            }

            if (encoder instanceof Encoder.BinaryStream)
            {
                Encoder.BinaryStream ebin = (Encoder.BinaryStream) encoder;
                WebSocketCoreConnection connection = session.getConnection();
                try (MessageOutputStream out = new MessageOutputStream(connection, connection.getInputBufferSize(), connection.getBufferPool()))
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
            LOG.warn("Unable to send Object " + data, t);
        }
    }

    @Override
    public void sendPing(ByteBuffer data) throws IOException, IllegalArgumentException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPing({})", BufferUtil.toDetailString(data));
        }
        // TODO: is this supposed to be a blocking call?
        super.sendPing(data, Callback.NOOP);
    }

    @Override
    public void sendPong(ByteBuffer data) throws IOException, IllegalArgumentException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPong({})", BufferUtil.toDetailString(data));
        }
        // TODO: is this supposed to be a blocking call?
        super.sendPong(data, Callback.NOOP);
    }
}

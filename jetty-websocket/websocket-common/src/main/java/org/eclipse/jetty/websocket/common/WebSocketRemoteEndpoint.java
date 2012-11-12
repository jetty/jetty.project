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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import javax.net.websocket.EncodeException;
import javax.net.websocket.RemoteEndpoint;
import javax.net.websocket.SendHandler;
import javax.net.websocket.SendResult;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.message.MessageOutputStream;
import org.eclipse.jetty.websocket.common.message.MessageWriter;

/**
 * Endpoint for Writing messages to the Remote websocket.
 */
public class WebSocketRemoteEndpoint implements RemoteEndpoint<Object>
{
    private static final Logger LOG = Log.getLogger(WebSocketRemoteEndpoint.class);
    public final LogicalConnection connection;
    public final OutgoingFrames outgoing;
    public MessageOutputStream stream;
    public MessageWriter writer;

    public WebSocketRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoing)
    {
        if (connection == null)
        {
            throw new IllegalArgumentException("LogicalConnection cannot be null");
        }
        this.connection = connection;
        this.outgoing = outgoing;
    }

    public InetSocketAddress getInetSocketAddress()
    {
        return connection.getRemoteAddress();
    }

    @Override
    public OutputStream getSendStream() throws IOException
    {
        if (isWriterActive())
        {
            throw new IOException("Cannot get OutputStream while Writer is open");
        }

        if (isStreamActive())
        {
            LOG.debug("getSendStream() -> (existing) {}",stream);
            return stream;
        }

        stream = new MessageOutputStream(connection,outgoing);
        LOG.debug("getSendStream() -> (new) {}",stream);
        return stream;
    }

    @Override
    public Writer getSendWriter() throws IOException
    {
        if (isStreamActive())
        {
            throw new IOException("Cannot get Writer while OutputStream is open");
        }

        if (isWriterActive())
        {
            LOG.debug("getSendWriter() -> (existing) {}",writer);
            return writer;
        }

        writer = new MessageWriter(connection,outgoing);
        LOG.debug("getSendWriter() -> (new) {}",writer);
        return writer;
    }

    private boolean isStreamActive()
    {
        if (stream == null)
        {
            return false;
        }

        return !stream.isClosed();
    }

    private boolean isWriterActive()
    {
        if (writer == null)
        {
            return false;
        }
        return !writer.isClosed();
    }

    /**
     * Internal
     * 
     * @param frame
     * @return
     */
    private Future<SendResult> sendAsyncFrame(WebSocketFrame frame)
    {
        try
        {
            connection.assertOutputOpen();
            return outgoing.outgoingFrame(frame);
        }
        catch (IOException e)
        {
            SendHandler handler = frame.getSendHandler();
            return new FailedFuture(handler, e);
        }
    }

    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        connection.assertOutputOpen();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBytes({})",BufferUtil.toDetailString(data));
        }
        Frame frame = WebSocketFrame.binary().setPayload(data);
        outgoing.outgoingFrame(frame);
    }

    @Override
    public Future<SendResult> sendBytes(ByteBuffer data, SendHandler completion)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBytes({}, {})",BufferUtil.toDetailString(data),completion);
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(data);
        frame.setSendHandler(completion);
        return sendAsyncFrame(frame);
    }

    private void sendFrame(Frame frame)
    {
        try
        {
            outgoing.outgoingFrame(frame);
        }
        catch (IOException e)
        {
            LOG.warn(e);
        }
    }

    @Override
    public void sendObject(Object o) throws IOException, EncodeException
    {
        // TODO Auto-generated method stub
    }

    @Override
    public Future<SendResult> sendObject(Object o, SendHandler handler)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void sendPartialBytes(ByteBuffer partialByte, boolean isLast) throws IOException
    {
        Frame frame = WebSocketFrame.binary().setPayload(partialByte).setFin(isLast);
        outgoing.outgoingFrame(frame);
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        Frame frame = WebSocketFrame.text(fragment).setFin(isLast);
        outgoing.outgoingFrame(frame);
    }

    @Override
    public void sendPing(ByteBuffer applicationData)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Ping with {}",BufferUtil.toDetailString(applicationData));
        }
        Frame frame = WebSocketFrame.ping().setPayload(applicationData);
        sendFrame(frame);
    }

    @Override
    public void sendPong(ByteBuffer applicationData)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Pong with {}",BufferUtil.toDetailString(applicationData));
        }
        Frame frame = WebSocketFrame.pong().setPayload(applicationData);
        sendFrame(frame);
    }

    @Override
    public void sendString(String text) throws IOException
    {
        Frame frame = WebSocketFrame.text(text);
        outgoing.outgoingFrame(frame);
    }

    @Override
    public Future<SendResult> sendString(String text, SendHandler completion)
    {
        WebSocketFrame frame = WebSocketFrame.text(text);
        frame.setSendHandler(completion);
        return sendAsyncFrame(frame);
    }
}

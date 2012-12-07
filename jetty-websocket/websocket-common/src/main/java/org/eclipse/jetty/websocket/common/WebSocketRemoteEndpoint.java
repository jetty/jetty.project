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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WriteResult;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.io.WriteResultFailedFuture;

/**
 * Endpoint for Writing messages to the Remote websocket.
 */
public class WebSocketRemoteEndpoint implements RemoteEndpoint
{
    private static final Logger LOG = Log.getLogger(WebSocketRemoteEndpoint.class);
    public final LogicalConnection connection;
    public final OutgoingFrames outgoing;

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

    /**
     * Internal
     * 
     * @param frame
     * @return
     */
    private Future<WriteResult> sendAsyncFrame(WebSocketFrame frame)
    {
        try
        {
            connection.assertOutputOpen();
            return outgoing.outgoingFrame(frame);
        }
        catch (IOException e)
        {
            return new WriteResultFailedFuture(e);
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
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(data);
        outgoing.outgoingFrame(frame);
    }

    @Override
    public Future<WriteResult> sendBytesByFuture(ByteBuffer data)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBytesByFuture({})",BufferUtil.toDetailString(data));
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(data);
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
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPartialBytes({}, {})",BufferUtil.toDetailString(fragment),isLast);
        }
        Frame frame = WebSocketFrame.binary().setPayload(fragment).setFin(isLast);
        outgoing.outgoingFrame(frame);
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPartialString({}, {})",fragment,isLast);
        }
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
    public Future<WriteResult> sendStringByFuture(String text)
    {
        WebSocketFrame frame = WebSocketFrame.text(text);
        return sendAsyncFrame(frame);
    }
}

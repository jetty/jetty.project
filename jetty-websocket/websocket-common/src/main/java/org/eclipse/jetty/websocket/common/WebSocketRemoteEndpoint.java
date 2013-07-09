//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;

/**
 * Endpoint for Writing messages to the Remote websocket.
 */
public class WebSocketRemoteEndpoint implements RemoteEndpoint
{
    private static final String PRIORMSG_ERROR = "Prior message pending, cannot start new message yet.";
    /** Type of Message */
    private static final int NONE = 0;
    private static final int TEXT = 1;
    private static final int BINARY = 2;
    private static final int CONTROL = 3;

    private static final Logger LOG = Log.getLogger(WebSocketRemoteEndpoint.class);
    public final LogicalConnection connection;
    public final OutgoingFrames outgoing;
    private final ReentrantLock msgLock = new ReentrantLock();
    private final AtomicInteger msgType = new AtomicInteger(NONE);

    public WebSocketRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoing)
    {
        if (connection == null)
        {
            throw new IllegalArgumentException("LogicalConnection cannot be null");
        }
        this.connection = connection;
        this.outgoing = outgoing;
    }

    private void blockingWrite(WebSocketFrame frame) throws IOException
    {
        Future<Void> fut = sendAsyncFrame(frame);
        try
        {
            fut.get(); // block till done
        }
        catch (ExecutionException e)
        {
            throw new IOException("Failed to write bytes",e.getCause());
        }
        catch (InterruptedException e)
        {
            throw new IOException("Failed to write bytes",e);
        }
    }

    public InetSocketAddress getInetSocketAddress()
    {
        return connection.getRemoteAddress();
    }

    /**
     * Internal
     * 
     * @param frame
     *            the frame to write
     * @return the future for the network write of the frame
     */
    private Future<Void> sendAsyncFrame(WebSocketFrame frame)
    {
        FutureWriteCallback future = new FutureWriteCallback();
        sendFrame(frame,future);
        return future;
    }

    /**
     * Blocking write of bytes.
     */
    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        if (msgLock.tryLock())
        {
            try
            {
                msgType.set(BINARY);
                connection.getIOState().assertOutputOpen();
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("sendBytes with {}",BufferUtil.toDetailString(data));
                }
                WebSocketFrame frame = WebSocketFrame.binary().setPayload(data);
                blockingWrite(frame);
            }
            finally
            {
                msgType.set(NONE);
                msgLock.unlock();
            }
        }
        else
        {
            throw new IllegalStateException(PRIORMSG_ERROR);
        }
    }

    @Override
    public Future<Void> sendBytesByFuture(ByteBuffer data)
    {
        msgType.set(BINARY);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendBytesByFuture with {}",BufferUtil.toDetailString(data));
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(data);
        return sendAsyncFrame(frame);
    }

    public void sendFrame(WebSocketFrame frame, WriteCallback callback)
    {
        try
        {
            connection.getIOState().assertOutputOpen();
            outgoing.outgoingFrame(frame,callback);
        }
        catch (IOException e)
        {
            callback.writeFailed(e);
        }
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        if (msgLock.tryLock())
        {
            try
            {
                if (msgType.get() == TEXT)
                {
                    throw new IllegalStateException("Prior TEXT message pending, cannot start new BINARY message yet.");
                }
                msgType.set(BINARY);

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("sendPartialBytes({}, {})",BufferUtil.toDetailString(fragment),isLast);
                }
                WebSocketFrame frame = WebSocketFrame.binary().setPayload(fragment).setFin(isLast);
                blockingWrite(frame);
            }
            finally
            {
                if (isLast)
                {
                    msgType.set(NONE);
                }
                msgLock.unlock();
            }
        }
        else
        {
            throw new IllegalStateException(PRIORMSG_ERROR);
        }
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        if (msgLock.tryLock())
        {
            try
            {
                if (msgType.get() == BINARY)
                {
                    throw new IllegalStateException("Prior BINARY message pending, cannot start new TEXT message yet.");
                }
                msgType.set(TEXT);

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("sendPartialString({}, {})",fragment,isLast);
                }
                WebSocketFrame frame = WebSocketFrame.text(fragment).setFin(isLast);
                blockingWrite(frame);

            }
            finally
            {
                if (isLast)
                {
                    msgType.set(NONE);
                }
                msgLock.unlock();
            }
        }
        else
        {
            throw new IllegalStateException(PRIORMSG_ERROR);
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        if (msgLock.tryLock())
        {
            try
            {
                msgType.set(CONTROL);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("sendPing with {}",BufferUtil.toDetailString(applicationData));
                }
                WebSocketFrame frame = WebSocketFrame.ping().setPayload(applicationData);
                blockingWrite(frame);
            }
            finally
            {
                msgType.set(NONE);
                msgLock.unlock();
            }
        }
        else
        {
            throw new IllegalStateException(PRIORMSG_ERROR);
        }
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        if (msgLock.tryLock())
        {
            try
            {
                msgType.set(CONTROL);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("sendPong with {}",BufferUtil.toDetailString(applicationData));
                }
                WebSocketFrame frame = WebSocketFrame.pong().setPayload(applicationData);
                blockingWrite(frame);
            }
            finally
            {
                msgType.set(NONE);
                msgLock.unlock();
            }
        }
        else
        {
            throw new IllegalStateException(PRIORMSG_ERROR);
        }
    }

    @Override
    public void sendString(String text) throws IOException
    {
        if (msgLock.tryLock())
        {
            try
            {
                msgType.set(TEXT);
                WebSocketFrame frame = WebSocketFrame.text(text);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("sendString with {}",BufferUtil.toDetailString(frame.getPayload()));
                }
                blockingWrite(WebSocketFrame.text(text));
            }
            finally
            {
                msgType.set(NONE);
                msgLock.unlock();
            }
        }
        else
        {
            throw new IllegalStateException(PRIORMSG_ERROR);
        }
    }

    @Override
    public Future<Void> sendStringByFuture(String text)
    {
        msgType.set(BINARY);
        WebSocketFrame frame = WebSocketFrame.text(text);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendStringByFuture with {}",BufferUtil.toDetailString(frame.getPayload()));
        }
        return sendAsyncFrame(frame);
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.BlockingWriteCallback.WriteBlocker;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.DataFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.PongFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.io.FrameFlusher;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;

/**
 * Endpoint for Writing messages to the Remote websocket.
 */
public class WebSocketRemoteEndpoint implements RemoteEndpoint
{
    private enum MsgType
    {
        BLOCKING,
        ASYNC,
        STREAMING,
        PARTIAL_TEXT,
        PARTIAL_BINARY
    }

    private static final WriteCallback NOOP_CALLBACK = new WriteCallback()
    {
        @Override
        public void writeSuccess()
        {
        }

        @Override
        public void writeFailed(Throwable x)
        {
        }
    };

    private static final Logger LOG = Log.getLogger(WebSocketRemoteEndpoint.class);

    private final static int ASYNC_MASK = 0x0000FFFF;
    private final static int BLOCK_MASK = 0x00010000;
    private final static int STREAM_MASK = 0x00020000;
    private final static int PARTIAL_TEXT_MASK = 0x00040000;
    private final static int PARTIAL_BINARY_MASK = 0x00080000;

    private final LogicalConnection connection;
    private final OutgoingFrames outgoing;
    private final AtomicInteger msgState = new AtomicInteger();
    private final BlockingWriteCallback blocker = new BlockingWriteCallback();
    private volatile BatchMode batchMode;

    public WebSocketRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoing)
    {
        this(connection, outgoing, BatchMode.AUTO);
    }

    public WebSocketRemoteEndpoint(LogicalConnection connection, OutgoingFrames outgoing, BatchMode batchMode)
    {
        if (connection == null)
        {
            throw new IllegalArgumentException("LogicalConnection cannot be null");
        }
        this.connection = connection;
        this.outgoing = outgoing;
        this.batchMode = batchMode;
    }

    private void blockingWrite(WebSocketFrame frame) throws IOException
    {
        try(WriteBlocker b=blocker.acquireWriteBlocker())
        {
            uncheckedSendFrame(frame, b);
            b.block();
        }
    }

    private boolean lockMsg(MsgType type)
    {
        // Blocking -> BLOCKING  ; Async -> ASYNC     ; Partial -> PARTIAL_XXXX ; Stream -> STREAMING
        // Blocking -> Pending!! ; Async -> BLOCKING  ; Partial -> Pending!!    ; Stream -> STREAMING 
        // Blocking -> BLOCKING  ; Async -> ASYNC     ; Partial -> Pending!!    ; Stream -> STREAMING
        // Blocking -> Pending!! ; Async -> STREAMING ; Partial -> Pending!!    ; Stream -> STREAMING
        // Blocking -> Pending!! ; Async -> Pending!! ; Partial -> PARTIAL_TEXT ; Stream -> Pending!!
        // Blocking -> Pending!! ; Async -> Pending!! ; Partial -> PARTIAL_BIN  ; Stream -> Pending!!

        while (true)
        {
            int state = msgState.get();

            switch (type)
            {
                case BLOCKING:
                    if ((state & (PARTIAL_BINARY_MASK + PARTIAL_TEXT_MASK)) != 0)
                        throw new IllegalStateException(String.format("Partial message pending %x for %s", state, type));
                    if ((state & BLOCK_MASK) != 0)
                        throw new IllegalStateException(String.format("Blocking message pending %x for %s", state, type));
                    if (msgState.compareAndSet(state, state | BLOCK_MASK))
                        return state == 0;
                    break;

                case ASYNC:
                    if ((state & (PARTIAL_BINARY_MASK + PARTIAL_TEXT_MASK)) != 0)
                        throw new IllegalStateException(String.format("Partial message pending %x for %s", state, type));
                    if ((state & ASYNC_MASK) == ASYNC_MASK)
                        throw new IllegalStateException(String.format("Too many async sends: %x", state));
                    if (msgState.compareAndSet(state, state + 1))
                        return state == 0;
                    break;

                case STREAMING:
                    if ((state & (PARTIAL_BINARY_MASK + PARTIAL_TEXT_MASK)) != 0)
                        throw new IllegalStateException(String.format("Partial message pending %x for %s", state, type));
                    if ((state & STREAM_MASK) != 0)
                        throw new IllegalStateException(String.format("Already streaming %x for %s", state, type));
                    if (msgState.compareAndSet(state, state | STREAM_MASK))
                        return state == 0;
                    break;

                case PARTIAL_BINARY:
                    if (state == PARTIAL_BINARY_MASK)
                        return false;
                    if (state == 0)
                    {
                        if (msgState.compareAndSet(0, state | PARTIAL_BINARY_MASK))
                            return true;
                    }
                    throw new IllegalStateException(String.format("Cannot send %s in state %x", type, state));

                case PARTIAL_TEXT:
                    if (state == PARTIAL_TEXT_MASK)
                        return false;
                    if (state == 0)
                    {
                        if (msgState.compareAndSet(0, state | PARTIAL_TEXT_MASK))
                            return true;
                    }
                    throw new IllegalStateException(String.format("Cannot send %s in state %x", type, state));
            }
        }
    }

    private void unlockMsg(MsgType type)
    {
        while (true)
        {
            int state = msgState.get();

            switch (type)
            {
                case BLOCKING:
                    if ((state & BLOCK_MASK) == 0)
                        throw new IllegalStateException(String.format("Not Blocking in state %x", state));
                    if (msgState.compareAndSet(state, state & ~BLOCK_MASK))
                        return;
                    break;

                case ASYNC:
                    if ((state & ASYNC_MASK) == 0)
                        throw new IllegalStateException(String.format("Not Async in %x", state));
                    if (msgState.compareAndSet(state, state - 1))
                        return;
                    break;

                case STREAMING:
                    if ((state & STREAM_MASK) == 0)
                        throw new IllegalStateException(String.format("Not Streaming in state %x", state));
                    if (msgState.compareAndSet(state, state & ~STREAM_MASK))
                        return;
                    break;

                case PARTIAL_BINARY:
                    if (msgState.compareAndSet(PARTIAL_BINARY_MASK, 0))
                        return;
                    throw new IllegalStateException(String.format("Not Partial Binary in state %x", state));

                case PARTIAL_TEXT:
                    if (msgState.compareAndSet(PARTIAL_TEXT_MASK, 0))
                        return;
                    throw new IllegalStateException(String.format("Not Partial Text in state %x", state));

            }
        }
    }

    /**
     * Get the InetSocketAddress for the established connection.
     *
     * @return the InetSocketAddress for the established connection. (or null, if the connection is no longer established)
     */
    public InetSocketAddress getInetSocketAddress()
    {
        if(connection == null)
            return null;
        return connection.getRemoteAddress();
    }

    /**
     * Internal
     *
     * @param frame the frame to write
     * @return the future for the network write of the frame
     */
    private Future<Void> sendAsyncFrame(WebSocketFrame frame)
    {
        FutureWriteCallback future = new FutureWriteCallback();
        uncheckedSendFrame(frame, future);
        return future;
    }

    /**
     * Blocking write of bytes.
     */
    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        lockMsg(MsgType.BLOCKING);
        try
        {
            connection.getIOState().assertOutputOpen();
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendBytes with {}", BufferUtil.toDetailString(data));
            }
            blockingWrite(new BinaryFrame().setPayload(data));
        }
        finally
        {
            unlockMsg(MsgType.BLOCKING);
        }
    }

    @Override
    public Future<Void> sendBytesByFuture(ByteBuffer data)
    {
        lockMsg(MsgType.ASYNC);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendBytesByFuture with {}", BufferUtil.toDetailString(data));
            }
            return sendAsyncFrame(new BinaryFrame().setPayload(data));
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    @Override
    public void sendBytes(ByteBuffer data, WriteCallback callback)
    {
        lockMsg(MsgType.ASYNC);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendBytes({}, {})", BufferUtil.toDetailString(data), callback);
            }
            uncheckedSendFrame(new BinaryFrame().setPayload(data), callback == null ? NOOP_CALLBACK : callback);
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    public void uncheckedSendFrame(WebSocketFrame frame, WriteCallback callback)
    {
        try
        {
            BatchMode batchMode = BatchMode.OFF;
            if (frame.isDataFrame())
                batchMode = getBatchMode();
            connection.getIOState().assertOutputOpen();
            outgoing.outgoingFrame(frame, callback, batchMode);
        }
        catch (IOException e)
        {
            callback.writeFailed(e);
        }
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        boolean first = lockMsg(MsgType.PARTIAL_BINARY);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendPartialBytes({}, {})", BufferUtil.toDetailString(fragment), isLast);
            }
            DataFrame frame = first ? new BinaryFrame() : new ContinuationFrame();
            frame.setPayload(fragment);
            frame.setFin(isLast);
            blockingWrite(frame);
        }
        finally
        {
            if (isLast)
                unlockMsg(MsgType.PARTIAL_BINARY);
        }
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        boolean first = lockMsg(MsgType.PARTIAL_TEXT);
        try
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendPartialString({}, {})", fragment, isLast);
            }
            DataFrame frame = first ? new TextFrame() : new ContinuationFrame();
            frame.setPayload(BufferUtil.toBuffer(fragment, StandardCharsets.UTF_8));
            frame.setFin(isLast);
            blockingWrite(frame);
        }
        finally
        {
            if (isLast)
                unlockMsg(MsgType.PARTIAL_TEXT);
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPing with {}", BufferUtil.toDetailString(applicationData));
        }
        sendAsyncFrame(new PingFrame().setPayload(applicationData));
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("sendPong with {}", BufferUtil.toDetailString(applicationData));
        }
        sendAsyncFrame(new PongFrame().setPayload(applicationData));
    }

    @Override
    public void sendString(String text) throws IOException
    {
        lockMsg(MsgType.BLOCKING);
        try
        {
            WebSocketFrame frame = new TextFrame().setPayload(text);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendString with {}", BufferUtil.toDetailString(frame.getPayload()));
            }
            blockingWrite(frame);
        }
        finally
        {
            unlockMsg(MsgType.BLOCKING);
        }
    }

    @Override
    public Future<Void> sendStringByFuture(String text)
    {
        lockMsg(MsgType.ASYNC);
        try
        {
            TextFrame frame = new TextFrame().setPayload(text);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendStringByFuture with {}", BufferUtil.toDetailString(frame.getPayload()));
            }
            return sendAsyncFrame(frame);
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    @Override
    public void sendString(String text, WriteCallback callback)
    {
        lockMsg(MsgType.ASYNC);
        try
        {
            TextFrame frame = new TextFrame().setPayload(text);
            if (LOG.isDebugEnabled())
            {
                LOG.debug("sendString({},{})", BufferUtil.toDetailString(frame.getPayload()), callback);
            }
            uncheckedSendFrame(frame, callback == null ? NOOP_CALLBACK : callback);
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    @Override
    public BatchMode getBatchMode()
    {
        return batchMode;
    }

    @Override
    public void setBatchMode(BatchMode batchMode)
    {
        this.batchMode = batchMode;
    }

    @Override
    public void flush() throws IOException
    {
        lockMsg(MsgType.ASYNC);
        try (WriteBlocker b = blocker.acquireWriteBlocker())
        {
            uncheckedSendFrame(FrameFlusher.FLUSH_FRAME, b);
            b.block();
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[batching=%b]", getClass().getSimpleName(), hashCode(), getBatchMode());
    }
}

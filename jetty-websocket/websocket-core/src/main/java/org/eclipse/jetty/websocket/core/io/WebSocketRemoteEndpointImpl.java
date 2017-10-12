//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.io;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;

/**
 * Represents the remote websocket endpoint, with facilities to send WebSocketFrames
 */
public class WebSocketRemoteEndpointImpl implements Closeable, WebSocketRemoteEndpoint
{
    public enum MsgType
    {
        ASYNC,
        PARTIAL_TEXT,
        PARTIAL_BINARY
    }

    private final static int ASYNC_MASK = 0x0000FFFF;
    private final static int PARTIAL_TEXT_MASK = 0x00010000;
    private final static int PARTIAL_BINARY_MASK = 0x00020000;

    /* Where outgoing frames are sent. */
    private final OutgoingFrames outgoing;
    protected final SharedBlockingCallback blocker = new SharedBlockingCallback();
    private final AtomicInteger msgState = new AtomicInteger();
    private AtomicBoolean open = new AtomicBoolean(false);
    private volatile BatchMode batchMode = BatchMode.AUTO;

    public WebSocketRemoteEndpointImpl(OutgoingFrames outgoing)
    {
        this.outgoing = outgoing;
    }

    /**
     * Internally onOpen the RemoteEndpoint
     */
    public void open()
    {
        open.set(true);
    }

    /**
     * Internally close the RemoteEndpoint to further use.
     */
    @Override
    public void close()
    {
        open.set(false);
    }

    /**
     * Asynchronous send of Close frame.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param statusCode the close status code to send
     * @param reason the close reason (can be null)
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendClose(int statusCode, String reason, Callback callback)
    {
        unlockedSendFrame(new CloseFrame().setPayload(statusCode, reason), callback);
    }

    /**
     * Asynchronous send of a whole Binary message.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param data the data being sent
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendBinary(ByteBuffer data, Callback callback)
    {
        assertIsOpen();
        lockMsg(MsgType.ASYNC);
        try
        {
            unlockedSendFrame(new BinaryFrame().setPayload(data), callback);
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    /**
     * Asynchronous send of a partial Binary message.
     * <p>
     * Be mindful of partial message order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     * </p>
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendPartialBinary(ByteBuffer fragment, boolean isLast, Callback callback)
    {
        assertIsOpen();
        boolean first = lockMsg(MsgType.PARTIAL_BINARY);
        try
        {
            DataFrame frame = first ? new BinaryFrame() : new ContinuationFrame();
            frame.setPayload(fragment);
            frame.setFin(isLast);
            unlockedSendFrame(frame, callback);
        }
        finally
        {
            if (isLast)
                unlockMsg(MsgType.PARTIAL_BINARY);
        }
    }

    /**
     * Asynchronous send of a partial Text message.
     * <p>
     * Be mindful of partial message order. Non-final pieces are
     * sent with isLast set to false. The final piece must be sent with isLast set to true.
     * </p>
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param fragment the piece of the message being sent
     * @param isLast true if this is the last piece of the partial bytes
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendPartialText(String fragment, boolean isLast, Callback callback)
    {
        assertIsOpen();
        boolean first = lockMsg(MsgType.PARTIAL_TEXT);
        try
        {
            DataFrame frame = first ? new TextFrame() : new ContinuationFrame();
            frame.setPayload(BufferUtil.toBuffer(fragment, StandardCharsets.UTF_8));
            frame.setFin(isLast);
            unlockedSendFrame(frame, callback);
        }
        finally
        {
            if (isLast)
                unlockMsg(MsgType.PARTIAL_TEXT);
        }
    }

    /**
     * Asynchronous send of a whole Text message.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param text the text being sent
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendText(String text, Callback callback)
    {
        assertIsOpen();
        lockMsg(MsgType.ASYNC);
        try
        {
            unlockedSendFrame(new TextFrame().setPayload(text), callback);
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    /**
     * Asynchronous send of a Ping frame.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param applicationData the data to be carried in the ping request
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendPing(ByteBuffer applicationData, Callback callback)
    {
        unlockedSendFrame(new PingFrame().setPayload(applicationData), callback);
    }

    /**
     * Asynchronous send of a Pong frame.
     * <p>
     * This can return before the message has actually been transmitted, the Callback provides
     * the means to be notified on success of failure of the send.
     * </p>
     *
     * @param applicationData the application data to be carried in the pong response.
     * @param callback callback to notify of success or failure of the write operation
     */
    @Override
    public void sendPong(ByteBuffer applicationData, Callback callback)
    {
        unlockedSendFrame(new PongFrame().setPayload(applicationData), callback);
    }

    /**
     * Flushes messages that may have been batched by the implementation.
     *
     * @throws IOException if the flush fails
     * @see #getBatchMode()
     */
    @Override
    public void flush() throws IOException
    {
        sendBlocking(FrameFlusher.FLUSH_FRAME);
    }

    protected void sendBlocking(WebSocketFrame frame) throws IOException
    {
        assertIsOpen();
        lockMsg(MsgType.ASYNC);
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendFrame(frame, b);
            b.block();
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    public void sendFrame(WebSocketFrame frame, Callback callback)
    {
        assertIsOpen();
        lockMsg(MsgType.ASYNC);
        try
        {
            BatchMode batchMode = BatchMode.OFF;
            if (frame.isDataFrame())
                batchMode = getBatchMode();
            outgoing.outgoingFrame(frame, callback, batchMode);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
        finally
        {
            unlockMsg(MsgType.ASYNC);
        }
    }

    public void unlockedSendFrame(WebSocketFrame frame, Callback callback)
    {
        assertIsOpen();
        try
        {
            BatchMode batchMode = BatchMode.OFF;
            if (frame.isDataFrame())
                batchMode = getBatchMode();
            outgoing.outgoingFrame(frame, callback == null ? Callback.NOOP : callback, batchMode);
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    /**
     * Get the batch mode behavior.
     *
     * @return the batch mode with which messages are sent.
     * @see #flush()
     */
    @Override
    public BatchMode getBatchMode()
    {
        return this.batchMode;
    }

    /**
     * Set the batch mode with which messages are sent.
     *
     * @param mode the batch mode to use
     * @see #flush()
     */
    @Override
    public void setBatchMode(BatchMode mode)
    {
        this.batchMode = mode;
    }

    private void assertIsOpen()
    {
        if (!open.get())
        {
            throw new WebSocketException("WSRemoteImpl not onOpen");
        }
    }

    protected boolean lockMsg(MsgType type)
    {
        while (true)
        {
            int state = msgState.get();

            switch (type)
            {
                case ASYNC:
                    if ((state & (PARTIAL_BINARY_MASK + PARTIAL_TEXT_MASK)) != 0)
                        throw new IllegalStateException(String.format("Partial message pending %x for %s", state, type));
                    if ((state & ASYNC_MASK) == ASYNC_MASK)
                        throw new IllegalStateException(String.format("Too many async sends: %x", state));
                    if (msgState.compareAndSet(state, state + 1))
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

    protected void unlockMsg(MsgType type)
    {
        while (true)
        {
            int state = msgState.get();

            switch (type)
            {
                case ASYNC:
                    if ((state & ASYNC_MASK) == 0)
                        throw new IllegalStateException(String.format("Not Async in %x", state));
                    if (msgState.compareAndSet(state, state - 1))
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
}

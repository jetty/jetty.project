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

package org.eclipse.jetty.websocket.common;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class JettyWebSocketRemoteEndpoint implements org.eclipse.jetty.websocket.api.RemoteEndpoint
{
    private final FrameHandler.Channel channel;
    private byte messageType = -1;
    private final SharedBlockingCallback blocker = new SharedBlockingCallback();
    private volatile BatchMode batchMode;

    public JettyWebSocketRemoteEndpoint(FrameHandler.Channel channel)
    {
        this.channel = channel;
        this.batchMode = BatchMode.AUTO;
    }

    /**
     * Initiate close of the Remote with no status code (no payload)
     *
     * @since 10.0
     */
    public void close()
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            channel.close(b);
        }
        catch (IOException e)
        {
            channel.close(Callback.NOOP);
        }
    }

    /**
     * Initiate close of the Remote with specified status code and optional reason phrase
     *
     * @param statusCode the status code (must be valid and can be sent)
     * @param reason optional reason code
     * @since 10.0
     */
    public void close(int statusCode, String reason)
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            channel.close(statusCode, reason, b);
        }
        catch (IOException e)
        {
            channel.close(Callback.NOOP);
        }
    }

    protected FrameHandler.Channel getChannel()
    {
        return channel;
    }

    private void sendBlocking(WebSocketFrame frame) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            BatchMode batchMode1 = BatchMode.OFF;
            if (frame.isDataFrame())
                batchMode1 = getBatchMode();
            channel.sendFrame(frame, b, batchMode1);
            b.block();
        }
    }

    public BatchMode getBatchMode()
    {
        return batchMode;
    }

    @Override
    public void sendBinary(ByteBuffer data) throws IOException
    {
        sendBlocking(new BinaryFrame().setPayload(data));
    }

    @Override
    public void sendPartialBinary(ByteBuffer fragment, boolean isLast) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendPartialBinary(fragment, isLast, b);
            b.block();
        }
    }

    @Override
    public void sendPartialText(String fragment, boolean isLast) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendPartialText(fragment, isLast, b);
            b.block();
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        channel.sendFrame(new PingFrame().setPayload(applicationData), Callback.NOOP, BatchMode.OFF);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        channel.sendFrame(new PongFrame().setPayload(applicationData), Callback.NOOP, BatchMode.OFF);
    }

    @Override
    public void sendText(String text) throws IOException
    {
        sendBlocking(new TextFrame().setPayload(text));
    }

    @Override
    public void sendBinary(ByteBuffer data, Callback callback)
    {
        channel.sendFrame(new BinaryFrame().setPayload(data), callback, getBatchMode());
    }

    @Override
    public void sendPartialBinary(ByteBuffer fragment, boolean isLast, Callback callback)
    {
        DataFrame frame;
        switch (messageType)
        {
            case -1: // new message
                frame = new BinaryFrame();
                messageType = OpCode.BINARY;
                break;
            case OpCode.BINARY:
                frame = new ContinuationFrame();
                break;
            default:
                callback.failed(new ProtocolException("Attempt to send Partial Binary during active opcode " + messageType));
                return;
        }

        frame.setPayload(fragment);
        frame.setFin(isLast);

        channel.sendFrame(frame, callback, getBatchMode());

        if (isLast)
        {
            messageType = -1;
        }
    }

    @Override
    public void sendPartialText(String fragment, boolean isLast, Callback callback)
    {
        DataFrame frame;
        switch (messageType)
        {
            case -1: // new message
                frame = new BinaryFrame();
                messageType = OpCode.TEXT;
                break;
            case OpCode.TEXT:
                frame = new ContinuationFrame();
                break;
            default:
                callback.failed(new ProtocolException("Attempt to send Partial Text during active opcode " + messageType));
                return;
        }

        frame.setPayload(BufferUtil.toBuffer(fragment, UTF_8));
        frame.setFin(isLast);

        channel.sendFrame(frame, callback, getBatchMode());

        if (isLast)
        {
            messageType = -1;
        }
    }

    @Override
    public void sendText(String text, Callback callback)
    {
        channel.sendFrame(new TextFrame().setPayload(text), callback, getBatchMode());
    }
}

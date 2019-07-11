//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.SharedBlockingCallback;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class JettyWebSocketRemoteEndpoint implements org.eclipse.jetty.websocket.api.RemoteEndpoint
{
    private final FrameHandler.CoreSession coreSession;
    private byte messageType = -1;
    private final SharedBlockingCallback blocker = new SharedBlockingCallback();
    private BatchMode batchMode;

    public JettyWebSocketRemoteEndpoint(FrameHandler.CoreSession coreSession, BatchMode batchMode)
    {
        this.coreSession = Objects.requireNonNull(coreSession);
        this.batchMode = batchMode;
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
            coreSession.close(b);
        }
        catch (IOException e)
        {
            coreSession.close(Callback.NOOP);
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
            coreSession.close(statusCode, reason, b);
        }
        catch (IOException e)
        {
            coreSession.close(Callback.NOOP);
        }
    }

    @Override
    public void sendString(String text) throws IOException
    {
        sendBlocking(new Frame(OpCode.TEXT).setPayload(text));
    }

    @Override
    public void sendString(String text, WriteCallback callback)
    {
        Callback cb = callback == null ? Callback.NOOP : Callback.from(callback::writeSuccess, callback::writeFailed);
        coreSession.sendFrame(new Frame(OpCode.TEXT).setPayload(text), cb, isBatch());
    }

    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        sendBlocking(new Frame(OpCode.BINARY).setPayload(data));
    }

    @Override
    public void sendBytes(ByteBuffer data, WriteCallback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.BINARY).setPayload(data),
            Callback.from(callback::writeSuccess, callback::writeFailed),
            isBatch());
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendPartialBytes(fragment, isLast, b);
            b.block();
        }
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast, WriteCallback callback)
    {
        sendPartialBytes(fragment, isLast, Callback.from(callback::writeSuccess, callback::writeFailed));
    }

    private void sendPartialBytes(ByteBuffer fragment, boolean isLast, Callback callback)
    {
        Frame frame;
        switch (messageType)
        {
            case -1: // new message
                frame = new Frame(OpCode.BINARY);
                messageType = OpCode.BINARY;
                break;
            case OpCode.BINARY:
                frame = new Frame(OpCode.CONTINUATION);
                break;
            default:
                callback.failed(new ProtocolException("Attempt to send Partial Binary during active opcode " + messageType));
                return;
        }

        frame.setPayload(fragment);
        frame.setFin(isLast);

        coreSession.sendFrame(frame, callback, isBatch());

        if (isLast)
        {
            messageType = -1;
        }
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            sendPartialText(fragment, isLast, b);
            b.block();
        }
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast, WriteCallback callback)
    {
        sendPartialText(fragment, isLast, Callback.from(callback::writeSuccess, callback::writeFailed));
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        sendBlocking(new Frame(OpCode.PING).setPayload(applicationData));
    }

    @Override
    public void sendPing(ByteBuffer applicationData, WriteCallback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.PING).setPayload(applicationData),
            Callback.from(callback::writeSuccess, callback::writeFailed), false);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        sendBlocking(new Frame(OpCode.PONG).setPayload(applicationData));
    }

    @Override
    public void sendPong(ByteBuffer applicationData, WriteCallback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.PONG).setPayload(applicationData),
            Callback.from(callback::writeSuccess, callback::writeFailed), false);
    }

    private void sendPartialText(String fragment, boolean isLast, Callback callback)
    {
        Frame frame;
        switch (messageType)
        {
            case -1: // new message
                frame = new Frame(OpCode.BINARY);
                messageType = OpCode.TEXT;
                break;
            case OpCode.TEXT:
                frame = new Frame(OpCode.CONTINUATION);
                break;
            default:
                callback.failed(new ProtocolException("Attempt to send Partial Text during active opcode " + messageType));
                return;
        }

        frame.setPayload(BufferUtil.toBuffer(fragment, UTF_8));
        frame.setFin(isLast);

        coreSession.sendFrame(frame, callback, isBatch());

        if (isLast)
        {
            messageType = -1;
        }
    }

    private void sendBlocking(Frame frame) throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            coreSession.sendFrame(frame, b, false);
            b.block();
        }
    }

    protected FrameHandler.CoreSession getCoreSession()
    {
        return coreSession;
    }

    @Override
    public org.eclipse.jetty.websocket.api.BatchMode getBatchMode()
    {
        return batchMode;
    }

    @Override
    public void setBatchMode(BatchMode mode)
    {
        batchMode = mode;
    }

    private boolean isBatch()
    {
        return BatchMode.ON == batchMode;
    }

    @Override
    public SocketAddress getRemoteAddress()
    {
        return coreSession.getRemoteAddress();
    }

    @Override
    public void flush() throws IOException
    {
        try (SharedBlockingCallback.Blocker b = blocker.acquire())
        {
            coreSession.flush(b);
            b.block();
        }
    }
}

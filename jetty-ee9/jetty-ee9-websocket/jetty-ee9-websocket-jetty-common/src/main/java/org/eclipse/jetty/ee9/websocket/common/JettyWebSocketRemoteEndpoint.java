//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.common;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.ee9.websocket.api.BatchMode;
import org.eclipse.jetty.ee9.websocket.api.WriteCallback;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketRemoteEndpoint implements org.eclipse.jetty.ee9.websocket.api.RemoteEndpoint
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyWebSocketRemoteEndpoint.class);

    private final CoreSession coreSession;
    private byte messageType = -1;
    private BatchMode batchMode;

    public JettyWebSocketRemoteEndpoint(CoreSession coreSession, BatchMode batchMode)
    {
        this.coreSession = Objects.requireNonNull(coreSession);
        this.batchMode = batchMode;
    }

    @Override
    public void sendString(String text) throws IOException
    {
        try (Frame frame = new Frame(OpCode.TEXT, text))
        {
            sendBlocking(frame);
        }
    }

    @Override
    public void sendString(String text, WriteCallback callback)
    {
        try (Frame frame = new Frame(OpCode.TEXT, text))
        {
            Callback cb = callback == null ? Callback.NOOP : Callback.from(callback::writeSuccess, callback::writeFailed);
            coreSession.sendFrame(frame, cb, isBatch());
        }
    }

    @Override
    public void sendBytes(ByteBuffer data) throws IOException
    {
        try (Frame frame = new Frame(OpCode.BINARY, RetainableByteBuffer.wrap(data)))
        {
            sendBlocking(frame);
        }
    }

    @Override
    public void sendBytes(ByteBuffer data, WriteCallback callback)
    {
        try (Frame frame = new Frame(OpCode.BINARY, RetainableByteBuffer.wrap(data)))
        {
            Callback cb = callback == null ? Callback.NOOP : Callback.from(callback::writeSuccess, callback::writeFailed);
            coreSession.sendFrame(frame, cb, isBatch());
        }
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast) throws IOException
    {
        FutureCallback b = new FutureCallback();
        sendPartialBytes(fragment, isLast, b);
        b.block();
    }

    @Override
    public void sendPartialBytes(ByteBuffer fragment, boolean isLast, WriteCallback callback)
    {
        sendPartialBytes(fragment, isLast, Callback.from(callback::writeSuccess, callback::writeFailed));
    }

    private void sendPartialBytes(ByteBuffer fragment, boolean isLast, Callback callback)
    {
        byte opCode;
        switch (messageType)
        {
            case -1 ->
            {
                messageType = OpCode.BINARY;
                opCode = OpCode.BINARY;
            }
            case OpCode.BINARY -> opCode = OpCode.CONTINUATION;
            default ->
            {
                callback.failed(new ProtocolException("Attempt to send Partial Binary during active opcode " + messageType));
                return;
            }
        }

        try (Frame frame = new Frame(opCode, isLast, RetainableByteBuffer.wrap(fragment)))
        {
            coreSession.sendFrame(frame, callback, isBatch());
            if (isLast)
                messageType = -1;
        }
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast) throws IOException
    {
        FutureCallback b = new FutureCallback();
        sendPartialText(fragment, isLast, b);
        b.block();
    }

    @Override
    public void sendPartialString(String fragment, boolean isLast, WriteCallback callback)
    {
        sendPartialText(fragment, isLast, Callback.from(callback::writeSuccess, callback::writeFailed));
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException
    {
        try (Frame frame = new Frame(OpCode.PING, RetainableByteBuffer.wrap(applicationData)))
        {
            sendBlocking(frame);
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData, WriteCallback callback)
    {
        try (Frame frame = new Frame(OpCode.PING, RetainableByteBuffer.wrap(applicationData)))
        {
            coreSession.sendFrame(frame, Callback.from(callback::writeSuccess, callback::writeFailed), false);
        }
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException
    {
        try (Frame frame = new Frame(OpCode.PONG, RetainableByteBuffer.wrap(applicationData)))
        {
            sendBlocking(frame);
        }
    }

    @Override
    public void sendPong(ByteBuffer applicationData, WriteCallback callback)
    {
        try (Frame frame = new Frame(OpCode.PONG, RetainableByteBuffer.wrap(applicationData)))
        {
            coreSession.sendFrame(frame, Callback.from(callback::writeSuccess, callback::writeFailed), false);
        }
    }

    private void sendPartialText(String fragment, boolean isLast, Callback callback)
    {
        byte opCode;
        switch (messageType)
        {
            case -1 ->
            {
                messageType = OpCode.TEXT;
                opCode = OpCode.TEXT;
            }
            case OpCode.TEXT -> opCode = OpCode.CONTINUATION;
            default ->
            {
                callback.failed(new ProtocolException("Attempt to send Partial Text during active opcode " + messageType));
                return;
            }
        }

        try (Frame frame = new Frame(opCode, isLast, fragment))
        {
            coreSession.sendFrame(frame, callback, isBatch());
            if (isLast)
                messageType = -1;
        }
    }

    private void sendBlocking(Frame frame) throws IOException
    {
        FutureCallback b = new FutureCallback();
        coreSession.sendFrame(frame, b, false);
        b.block();
    }

    @Override
    public BatchMode getBatchMode()
    {
        return batchMode;
    }

    @Override
    public void setBatchMode(BatchMode mode)
    {
        batchMode = mode;
    }

    @Override
    public int getMaxOutgoingFrames()
    {
        return coreSession.getMaxOutgoingFrames();
    }

    @Override
    public void setMaxOutgoingFrames(int maxOutgoingFrames)
    {
        coreSession.setMaxOutgoingFrames(maxOutgoingFrames);
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
        FutureCallback b = new FutureCallback();
        coreSession.flush(b);
        b.block();
    }
}

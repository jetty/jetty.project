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

package org.eclipse.jetty.websocket.common;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.common.internal.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.OutgoingEntry;
import org.eclipse.jetty.websocket.core.messages.MessageSink;
import org.eclipse.jetty.websocket.core.messages.StringMessageSink;
import org.eclipse.jetty.websocket.core.util.MethodHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutgoingMessageCapture extends CoreSession.Empty implements CoreSession
{
    private static final Logger LOG = LoggerFactory.getLogger(OutgoingMessageCapture.class);

    public BlockingQueue<String> textMessages = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new LinkedBlockingDeque<>();
    public BlockingQueue<String> events = new LinkedBlockingDeque<>();

    private final ByteBufferPool bufferPool = ByteBufferPool.NON_POOLING;
    private final MethodHandle wholeTextHandle;
    private final MethodHandle wholeBinaryHandle;
    private MessageSink messageSink;

    public OutgoingMessageCapture()
    {
        MethodHandles.Lookup lookup = JettyWebSocketFrameHandlerFactory.getApplicationMethodHandleLookup(this.getClass());
        try
        {
            MethodHandle text = lookup.findVirtual(this.getClass(), "onWholeText", MethodType.methodType(Void.TYPE, String.class));
            this.wholeTextHandle = text.bindTo(this);

            MethodHandle binary = lookup.findVirtual(this.getClass(), "onWholeBinary", MethodType.methodType(Void.TYPE, ByteBuffer.class, Callback.class));
            this.wholeBinaryHandle = binary.bindTo(this);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to setup OutgoingMessageCapture", e);
        }
    }

    @Override
    public void sendFrame(OutgoingEntry entry)
    {
        Frame frame = entry.getFrame();
        ByteBuffer payload = frame.getPayload();
        switch (frame.getOpCode())
        {
            case OpCode.CLOSE:
            {
                CloseStatus closeStatus = new CloseStatus(payload);
                String event = String.format("CLOSE:%s:%s", CloseStatus.codeString(closeStatus.getCode()), closeStatus.getReason());
                if (LOG.isDebugEnabled())
                    LOG.debug(event);
                events.add(event);
                break;
            }
            case OpCode.PING:
            {
                String event = String.format("PING:%s", dataHint(payload));
                if (LOG.isDebugEnabled())
                    LOG.debug(event);
                events.add(event);
                break;
            }
            case OpCode.PONG:
            {
                String event = String.format("PONG:%s", dataHint(payload));
                if (LOG.isDebugEnabled())
                    LOG.debug(event);
                events.add(event);
                break;
            }
            case OpCode.TEXT:
            {
                String event = String.format("TEXT:fin=%b:len=%d", frame.isFin(), frame.getPayloadLength());
                if (LOG.isDebugEnabled())
                    LOG.debug(event);
                events.add(event);
                messageSink = new StringMessageSink(this, MethodHolder.from(wholeTextHandle), true);
                break;
            }
            case OpCode.BINARY:
            {
                String event = String.format("BINARY:fin=%b:len=%d", frame.isFin(), frame.getPayloadLength());
                if (LOG.isDebugEnabled())
                    LOG.debug(event);
                events.add(event);
                messageSink = new ByteBufferMessageSink(this, MethodHolder.from(wholeBinaryHandle), true);
                break;
            }
            case OpCode.CONTINUATION:
            {
                String event = String.format("CONTINUATION:fin=%b:len=%d", frame.isFin(), frame.getPayloadLength());
                if (LOG.isDebugEnabled())
                    LOG.debug(event);
                events.add(event);
                break;
            }
        }

        if (OpCode.isDataFrame(frame.getOpCode()))
        {
            Frame copy = Frame.copy(frame);
            messageSink.accept(copy, org.eclipse.jetty.util.Callback.from(() -> {}, Throwable::printStackTrace));
            if (frame.isFin())
                messageSink = null;
        }

        payload.position(payload.limit());
        entry.getCallback().succeeded();
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    @SuppressWarnings("unused")
    public void onWholeText(String msg)
    {
        this.textMessages.offer(msg);
    }

    @SuppressWarnings("unused")
    public void onWholeBinary(ByteBuffer buf, Callback callback)
    {
        this.binaryMessages.offer(BufferUtil.copy(buf));
        buf.position(buf.limit());
        callback.succeed();
    }

    private String dataHint(ByteBuffer payload)
    {
        if (payload == null)
            return "<null>";

        StringBuilder hint = new StringBuilder();
        hint.append('[');
        ByteBuffer sliced = payload.slice();
        if (sliced.remaining() > 20)
        {
            sliced.limit(20);
            hint.append(Hex.asHex(sliced));
            hint.append("...");
        }
        else
        {
            hint.append(Hex.asHex(sliced));
        }
        hint.append(']');
        return hint.toString();
    }
}

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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.common.message.ByteBufferMessageSink;
import org.eclipse.jetty.websocket.common.message.StringMessageSink;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;

public class OutgoingMessageCapture extends FrameHandler.CoreSession.Empty implements FrameHandler.CoreSession
{
    private static final Logger LOG = Log.getLogger(OutgoingMessageCapture.class);

    public BlockingQueue<String> textMessages = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new LinkedBlockingDeque<>();
    public BlockingQueue<String> events = new LinkedBlockingDeque<>();

    private final MethodHandle wholeTextHandle;
    private final MethodHandle wholeBinaryHandle;
    private MessageSink messageSink;
    private long maxMessageSize = 2 * 1024 * 1024;

    public OutgoingMessageCapture()
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try
        {
            MethodHandle text = lookup.findVirtual(this.getClass(), "onWholeText", MethodType.methodType(Void.TYPE, String.class));
            this.wholeTextHandle = text.bindTo(this);

            MethodHandle binary = lookup.findVirtual(this.getClass(), "onWholeBinary", MethodType.methodType(Void.TYPE, ByteBuffer.class));
            this.wholeBinaryHandle = binary.bindTo(this);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to setup OutgoingMessageCapture", e);
        }
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        switch (frame.getOpCode())
        {
            case OpCode.CLOSE:
            {
                CloseStatus closeStatus = new CloseStatus(frame.getPayload());
                String event = String.format("CLOSE:%s:%s", CloseStatus.codeString(closeStatus.getCode()), closeStatus.getReason());
                LOG.debug(event);
                events.offer(event);
            }
            break;
            case OpCode.PING:
            {
                String event = String.format("PING:%s", dataHint(frame.getPayload()));
                LOG.debug(event);
                events.offer(event);
            }
            break;
            case OpCode.PONG:
            {
                String event = String.format("PONG:%s", dataHint(frame.getPayload()));
                LOG.debug(event);
                events.offer(event);
            }
            break;
            case OpCode.TEXT:
            {
                String event = String.format("TEXT:fin=%b:len=%d", frame.isFin(), frame.getPayloadLength());
                LOG.debug(event);
                events.offer(event);
                messageSink = new StringMessageSink(null, wholeTextHandle, getFakeSession());
            }
            break;
            case OpCode.BINARY:
            {
                String event = String.format("BINARY:fin=%b:len=%d", frame.isFin(), frame.getPayloadLength());
                LOG.debug(event);
                events.offer(event);
                messageSink = new ByteBufferMessageSink(null, wholeBinaryHandle, getFakeSession());
            }
            break;
            case OpCode.CONTINUATION:
            {
                String event = String.format("CONTINUATION:fin=%b:len=%d", frame.isFin(), frame.getPayloadLength());
                LOG.debug(event);
                events.offer(event);
            }
            break;
        }

        if (OpCode.isDataFrame(frame.getOpCode()))
        {
            messageSink.accept(frame, callback);
            if (frame.isFin())
            {
                messageSink = null;
            }
        }
        else
        {
            callback.succeeded();
        }
    }

    @SuppressWarnings("unused")
    public void onWholeText(String msg)
    {
        this.textMessages.offer(msg);
    }

    @SuppressWarnings("unused")
    public void onWholeBinary(ByteBuffer buf)
    {
        ByteBuffer copy = null;
        if (buf != null)
        {
            copy = ByteBuffer.allocate(buf.remaining());
            copy.put(buf);
            copy.flip();
        }
        this.binaryMessages.offer(copy);
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

    private Session getFakeSession()
    {
        return new Session()
        {
            @Override
            public void close()
            {
            }

            @Override
            public void close(org.eclipse.jetty.websocket.api.CloseStatus closeStatus)
            {
            }

            @Override
            public void close(int statusCode, String reason)
            {
            }

            @Override
            public void disconnect()
            {
            }

            @Override
            public SocketAddress getLocalAddress()
            {
                return null;
            }

            @Override
            public String getProtocolVersion()
            {
                return null;
            }

            @Override
            public RemoteEndpoint getRemote()
            {
                return null;
            }

            @Override
            public SocketAddress getRemoteAddress()
            {
                return null;
            }

            @Override
            public UpgradeRequest getUpgradeRequest()
            {
                return null;
            }

            @Override
            public UpgradeResponse getUpgradeResponse()
            {
                return null;
            }

            @Override
            public boolean isOpen()
            {
                return false;
            }

            @Override
            public boolean isSecure()
            {
                return false;
            }

            @Override
            public SuspendToken suspend()
            {
                return null;
            }

            @Override
            public WebSocketBehavior getBehavior()
            {
                return null;
            }

            @Override
            public Duration getIdleTimeout()
            {
                return null;
            }

            @Override
            public int getInputBufferSize()
            {
                return 0;
            }

            @Override
            public int getOutputBufferSize()
            {
                return 0;
            }

            @Override
            public long getMaxBinaryMessageSize()
            {
                return maxMessageSize;
            }

            @Override
            public long getMaxTextMessageSize()
            {
                return maxMessageSize;
            }

            @Override
            public long getMaxFrameSize()
            {
                return 0;
            }

            @Override
            public boolean isAutoFragment()
            {
                return false;
            }

            @Override
            public void setIdleTimeout(Duration duration)
            {
            }

            @Override
            public void setInputBufferSize(int size)
            {
            }

            @Override
            public void setOutputBufferSize(int size)
            {
            }

            @Override
            public void setMaxBinaryMessageSize(long size)
            {
            }

            @Override
            public void setMaxTextMessageSize(long size)
            {
            }

            @Override
            public void setMaxFrameSize(long maxFrameSize)
            {
            }

            @Override
            public void setAutoFragment(boolean autoFragment)
            {
            }
        };
    }
}

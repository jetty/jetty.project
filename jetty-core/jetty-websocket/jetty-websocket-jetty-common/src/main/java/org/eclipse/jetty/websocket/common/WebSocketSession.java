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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class WebSocketSession implements Session, Dumpable
{
    private final CoreSession coreSession;
    private final JettyWebSocketFrameHandler frameHandler;
    private final UpgradeRequest upgradeRequest;
    private final UpgradeResponse upgradeResponse;
    private byte messageType = OpCode.UNDEFINED;

    public WebSocketSession(WebSocketContainer container, CoreSession coreSession, JettyWebSocketFrameHandler frameHandler)
    {
        this.frameHandler = Objects.requireNonNull(frameHandler);
        this.coreSession = Objects.requireNonNull(coreSession);
        this.upgradeRequest = frameHandler.getUpgradeRequest();
        this.upgradeResponse = frameHandler.getUpgradeResponse();
        container.notifySessionListeners((listener) -> listener.onWebSocketSessionCreated(this));
    }

    @Override
    public void demand()
    {
        if (frameHandler.isAutoDemand())
            throw new IllegalStateException("auto-demanding endpoint cannot explicitly demand");
        coreSession.demand(1);
    }

    @Override
    public void sendBinary(ByteBuffer buffer, Callback callback)
    {
        callback = Objects.requireNonNullElse(callback, Callback.NOOP);
        coreSession.sendFrame(new Frame(OpCode.BINARY).setPayload(buffer),
            org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail),
            false);
    }

    @Override
    public void sendPartialBinary(ByteBuffer buffer, boolean last, Callback callback)
    {
        callback = Objects.requireNonNullElse(callback, Callback.NOOP);
        Frame frame = switch (messageType)
        {
            case OpCode.UNDEFINED ->
            {
                // new message
                messageType = OpCode.BINARY;
                yield new Frame(OpCode.BINARY);
            }
            case OpCode.BINARY -> new Frame(OpCode.CONTINUATION);
            default ->
            {
                callback.fail(new ProtocolException("Attempt to send partial BINARY during " + OpCode.name(messageType)));
                yield null;
            }
        };

        if (frame != null)
        {
            frame.setPayload(buffer);
            frame.setFin(last);

            var cb = org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail);
            coreSession.sendFrame(frame, cb, false);

            if (last)
                messageType = OpCode.UNDEFINED;
        }
    }

    @Override
    public void sendText(String text, Callback callback)
    {
        callback = Objects.requireNonNullElse(callback, Callback.NOOP);
        var cb = org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail);
        coreSession.sendFrame(new Frame(OpCode.TEXT).setPayload(text), cb, false);
    }

    @Override
    public void sendPartialText(String text, boolean last, Callback callback)
    {
        Frame frame = switch (messageType)
        {
            case OpCode.UNDEFINED ->
            {
                // new message
                messageType = OpCode.TEXT;
                yield new Frame(OpCode.TEXT);
            }
            case OpCode.TEXT -> new Frame(OpCode.CONTINUATION);
            default ->
            {
                callback.fail(new ProtocolException("Attempt to send partial TEXT during " + OpCode.name(messageType)));
                yield null;
            }
        };

        if (frame != null)
        {
            frame.setPayload(BufferUtil.toBuffer(text, UTF_8));
            frame.setFin(last);

            var cb = org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail);
            coreSession.sendFrame(frame, cb, false);

            if (last)
                messageType = OpCode.UNDEFINED;
        }
    }

    @Override
    public void sendPing(ByteBuffer applicationData, Callback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.PING).setPayload(applicationData),
            org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail), false);
    }

    @Override
    public void sendPong(ByteBuffer applicationData, Callback callback)
    {
        coreSession.sendFrame(new Frame(OpCode.PONG).setPayload(applicationData),
            org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail), false);
    }

    @Override
    public void close(int statusCode, String reason, Callback callback)
    {
        coreSession.close(statusCode, reason, org.eclipse.jetty.util.Callback.from(callback::succeed, callback::fail));
    }

    @Override
    public Duration getIdleTimeout()
    {
        return coreSession.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(Duration duration)
    {
        coreSession.setIdleTimeout(duration);
    }

    @Override
    public int getInputBufferSize()
    {
        return coreSession.getInputBufferSize();
    }

    @Override
    public void setInputBufferSize(int size)
    {
        coreSession.setInputBufferSize(size);
    }

    @Override
    public int getOutputBufferSize()
    {
        return coreSession.getOutputBufferSize();
    }

    @Override
    public void setOutputBufferSize(int size)
    {
        coreSession.setOutputBufferSize(size);
    }

    @Override
    public long getMaxBinaryMessageSize()
    {
        return coreSession.getMaxBinaryMessageSize();
    }

    @Override
    public void setMaxBinaryMessageSize(long size)
    {
        coreSession.setMaxBinaryMessageSize(size);
    }

    @Override
    public long getMaxTextMessageSize()
    {
        return coreSession.getMaxTextMessageSize();
    }

    @Override
    public void setMaxTextMessageSize(long size)
    {
        coreSession.setMaxTextMessageSize(size);
    }

    @Override
    public long getMaxFrameSize()
    {
        return coreSession.getMaxFrameSize();
    }

    @Override
    public void setMaxFrameSize(long maxFrameSize)
    {
        coreSession.setMaxFrameSize(maxFrameSize);
    }

    @Override
    public boolean isAutoFragment()
    {
        return coreSession.isAutoFragment();
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {
        coreSession.setAutoFragment(autoFragment);
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

    @Override
    public String getProtocolVersion()
    {
        return upgradeRequest.getProtocolVersion();
    }

    @Override
    public boolean isOpen()
    {
        return coreSession.isOutputOpen();
    }

    @Override
    public boolean isSecure()
    {
        return upgradeRequest.isSecure();
    }

    @Override
    public void disconnect()
    {
        coreSession.abort();
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return coreSession.getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return coreSession.getRemoteAddress();
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return this.upgradeRequest;
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return this.upgradeResponse;
    }

    public CoreSession getCoreSession()
    {
        return coreSession;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, upgradeRequest, coreSession, frameHandler);
    }

    @Override
    public String dumpSelf()
    {
        return String.format("%s@%x[idleTimeout=%dms]",
            this.getClass().getSimpleName(), hashCode(),
            getIdleTimeout().toMillis());
    }

    @Override
    public String toString()
    {
        return String.format("WebSocketSession[to=%s,%s,%s]", getIdleTimeout(), coreSession, frameHandler);
    }
}

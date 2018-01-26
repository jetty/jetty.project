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

package org.eclipse.jetty.websocket.jsr356;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.AbstractPartialFrameHandler;
import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;

public class JavaxWebSocketFrameHandler extends AbstractPartialFrameHandler
{
    private final Logger log;
    private final JavaxWebSocketContainer container;
    private final Object endpointInstance;
    private final WebSocketPolicy policy;
    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private MethodHandle textHandle;
    private final Class<? extends MessageSink> textSinkClass;
    private MethodHandle binaryHandle;
    private final Class<? extends MessageSink> binarySinkClass;
    private MethodHandle pongHandle;
    /**
     * Immutable HandshakeRequest available via Session
     */
    private final HandshakeRequest handshakeRequest;
    /**
     * Immutable HandshakeResponse available via Session
     */
    private final HandshakeResponse handshakeResponse;
    private final String id;
    private final EndpointConfig endpointConfig;
    private final CompletableFuture<Session> futureSession;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private JavaxWebSocketSession session;

    public JavaxWebSocketFrameHandler(JavaxWebSocketContainer container,
                                      Object endpointInstance, WebSocketPolicy upgradePolicy,
                                      HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse,
                                      MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      MethodHandle textHandle, MethodHandle binaryHandle,
                                      Class<? extends MessageSink> textSinkClass,
                                      Class<? extends MessageSink> binarySinkClass,
                                      MethodHandle pongHandle,
                                      String id,
                                      EndpointConfig endpointConfig,
                                      CompletableFuture<Session> futureSession)
    {
        this.log = Log.getLogger(endpointInstance.getClass());

        this.container = container;
        this.endpointInstance = endpointInstance;
        this.policy = upgradePolicy;
        this.handshakeRequest = handshakeRequest;
        this.handshakeResponse = handshakeResponse;

        this.openHandle = openHandle;
        this.closeHandle = closeHandle;
        this.errorHandle = errorHandle;
        this.textHandle = textHandle;
        this.binaryHandle = binaryHandle;
        this.textSinkClass = textSinkClass;
        this.binarySinkClass = binarySinkClass;
        this.pongHandle = pongHandle;

        this.id = id;
        this.endpointConfig = endpointConfig;
        this.futureSession = futureSession;
    }

    public Object getEndpoint()
    {
        return endpointInstance;
    }

    public EndpointConfig getEndpointConfig()
    {
        return endpointConfig;
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public JavaxWebSocketSession getSession()
    {
        return session;
    }

    public Logger getLog()
    {
        return this.log;
    }

    public boolean hasTextSink()
    {
        return this.textSink != null;
    }

    public boolean hasBinarySink()
    {
        return this.binarySink != null;
    }

    @Override
    public void onClosed(CloseStatus closeStatus) throws Exception
    {
        // TODO: FrameHandler cleanup?
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void onError(Throwable cause)
    {
        futureSession.completeExceptionally(cause);

        if (errorHandle == null)
        {
            log.warn("Unhandled Error: Endpoint " + endpointInstance.getClass().getName() + " missing onError handler", cause);
            return;
        }

        try
        {
            errorHandle.invoke(cause);
        }
        catch (Throwable t)
        {
            WebSocketException wsError = new WebSocketException(endpointInstance.getClass().getName() + " ERROR method error: " + cause.getMessage(), t);
            wsError.addSuppressed(cause);
            throw wsError;
        }
    }

    @Override
    public void onOpen(Channel channel) throws Exception
    {
        session = new JavaxWebSocketSession(container, channel, this, handshakeRequest, handshakeResponse, id, endpointConfig);

        openHandle = JavaxWebSocketFrameHandlerFactory.bindTo(openHandle, session);
        closeHandle = JavaxWebSocketFrameHandlerFactory.bindTo(closeHandle, session);
        errorHandle = JavaxWebSocketFrameHandlerFactory.bindTo(errorHandle, session);
        textHandle = JavaxWebSocketFrameHandlerFactory.bindTo(textHandle, session);
        binaryHandle = JavaxWebSocketFrameHandlerFactory.bindTo(binaryHandle, session);
        pongHandle = JavaxWebSocketFrameHandlerFactory.bindTo(pongHandle, session);

        if (textHandle != null)
        {
            textSink = JavaxWebSocketFrameHandlerFactory.createMessageSink(textHandle, textSinkClass, getPolicy(), container.getExecutor());
        }

        if (binaryHandle != null)
        {
            binarySink = JavaxWebSocketFrameHandlerFactory.createMessageSink(binaryHandle, binarySinkClass, getPolicy(), container.getExecutor());
        }

        if (openHandle != null)
        {
            try
            {
                openHandle.invoke();
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " OPEN method error: " + cause.getMessage(), cause);
            }
        }

        futureSession.complete(session);
    }

    public String toString()
    {
        StringBuilder ret = new StringBuilder();
        ret.append(this.getClass().getSimpleName());
        ret.append('@').append(Integer.toHexString(this.hashCode()));
        ret.append("[endpoint=");
        if (endpointInstance == null)
        {
            ret.append("<null>");
        }
        else if (endpointInstance instanceof ConfiguredEndpoint)
        {
            ret.append(((ConfiguredEndpoint) endpointInstance).getRawEndpoint().getClass().getName());
        }
        else
        {
            ret.append(endpointInstance.getClass().getName());
        }
        ret.append(']');
        return ret.toString();
    }

    private void acceptMessage(Frame frame, Callback callback)
    {
        // No message sink is active
        if (activeMessageSink == null)
            return;

        // Accept the payload into the message sink
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = binarySink;

        acceptMessage(frame, callback);
    }

    @Override
    public void onClose(Frame frame, Callback callback)
    {
        if (closeHandle != null)
        {
            try
            {
                CloseStatus close = CloseFrame.toCloseStatus(frame.getPayload());
                CloseReason closeReason = new CloseReason(CloseReason.CloseCodes.getCloseCode(close.getCode()), close.getReason());
                closeHandle.invoke(closeReason);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " CLOSE method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    @Override
    public void onPing(Frame frame, Callback callback)
    {
        ByteBuffer payload = BufferUtil.EMPTY_BUFFER;

        if (frame.hasPayload())
        {
            payload = ByteBuffer.allocate(frame.getPayloadLength());
            BufferUtil.put(frame.getPayload(), payload);
        }
        channel.sendFrame(new PongFrame().setPayload(payload), Callback.NOOP, BatchMode.OFF);
        callback.succeeded();
    }

    @Override
    public void onPong(Frame frame, Callback callback)
    {
        if (pongHandle != null)
        {
            try
            {
                ByteBuffer payload = frame.getPayload();
                if (payload == null)
                    payload = BufferUtil.EMPTY_BUFFER;

                pongHandle.invoke(payload);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " PONG method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    @Override
    public void onText(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = textSink;

        acceptMessage(frame, callback);
    }
}

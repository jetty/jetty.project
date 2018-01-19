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

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.ReadOnlyDelegatedFrame;

public class JettyWebSocketFrameHandler implements FrameHandler
{
    private final Logger log;
    private final WebSocketContainerContext container;
    private final Object endpointInstance;
    private final WebSocketPolicy policy;
    private final MethodHandle openHandle;
    private final MethodHandle closeHandle;
    private final MethodHandle errorHandle;
    private final MethodHandle textHandle;
    private final Class<? extends MessageSink> textSinkClass;
    private final MethodHandle binaryHandle;
    private final Class<? extends MessageSink> binarySinkClass;
    private final MethodHandle frameHandle;
    private final MethodHandle pingHandle;
    private final MethodHandle pongHandle;
    /**
     * Immutable HandshakeRequest available via Session
     */
    private final HandshakeRequest handshakeRequest;
    /**
     * Immutable HandshakeResponse available via Session
     */
    private final HandshakeResponse handshakeResponse;
    private final CompletableFuture<Session> futureSession;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private WebSocketSessionImpl session;

    public JettyWebSocketFrameHandler(WebSocketContainerContext container,
                                      Object endpointInstance, WebSocketPolicy endpointPolicy,
                                      HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse,
                                      MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      MethodHandle textHandle, MethodHandle binaryHandle,
                                      Class<? extends MessageSink> textSinkClass,
                                      Class<? extends MessageSink> binarySinkClass,
                                      MethodHandle frameHandle,
                                      MethodHandle pingHandle, MethodHandle pongHandle,
                                      CompletableFuture<Session> futureSession)
    {
        this.log = Log.getLogger(endpointInstance.getClass());

        this.container = container;
        this.endpointInstance = endpointInstance;
        this.policy = endpointPolicy;
        this.handshakeRequest = handshakeRequest;
        this.handshakeResponse = handshakeResponse;

        this.openHandle = openHandle;
        this.closeHandle = closeHandle;
        this.errorHandle = errorHandle;
        this.textHandle = textHandle;
        this.binaryHandle = binaryHandle;
        this.textSinkClass = textSinkClass;
        this.binarySinkClass = binarySinkClass;
        this.frameHandle = frameHandle;
        this.pingHandle = pingHandle;
        this.pongHandle = pongHandle;

        this.futureSession = futureSession;
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public WebSocketSessionImpl getSession()
    {
        return session;
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
    public void onFrame(Frame frame, Callback callback) throws Exception
    {
        // Send to raw frame handling on user side (eg: WebSocketFrameListener)
        if (frameHandle != null)
        {
            try
            {
                frameHandle.invoke(new ReadOnlyDelegatedFrame(frame));
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " FRAME method error: " + cause.getMessage(), cause);
            }
        }

        switch (frame.getOpCode())
        {
            case OpCode.CLOSE:
                onClose(frame, callback);
                break;
            case OpCode.PING:
                onPing(frame, callback);
                break;
            case OpCode.PONG:
                onPong(frame, callback);
                break;
            case OpCode.TEXT:
                onText(frame, callback);
                break;
            case OpCode.BINARY:
                onBinary(frame, callback);
                break;
            case OpCode.CONTINUATION:
                onContinuation(frame, callback);
                break;
        }
    }

    @Override
    public void onOpen(Channel channel) throws Exception
    {
        JettyWebSocketRemoteEndpoint remote = new JettyWebSocketRemoteEndpoint(channel);
        session = new WebSocketSessionImpl(container, remote, handshakeRequest, handshakeResponse);

        if (textHandle != null)
        {
            MethodHandle handle = JettyWebSocketFrameHandlerFactory.bindTo(textHandle, session);
            textSink = JettyWebSocketFrameHandlerFactory.createMessageSink(handle, textSinkClass, getPolicy(), container.getExecutor());
        }

        if (binaryHandle != null)
        {
            MethodHandle handle = JettyWebSocketFrameHandlerFactory.bindTo(binaryHandle, session);
            binarySink = JettyWebSocketFrameHandlerFactory.createMessageSink(handle, binarySinkClass, getPolicy(), container.getExecutor());
        }

        if (openHandle != null)
        {
            MethodHandle handle = JettyWebSocketFrameHandlerFactory.bindTo(openHandle, session);

            try
            {
                handle.invoke();
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
        return String.format("%s@%x[%s]", this.getClass().getSimpleName(), this.hashCode(), endpointInstance.getClass().getName());
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

    private void onBinary(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = binarySink;

        acceptMessage(frame, callback);
    }

    private void onClose(Frame frame, Callback callback)
    {
        if (closeHandle != null)
        {
            try
            {
                CloseStatus close = CloseFrame.toCloseStatus(frame.getPayload());
                closeHandle.invoke(close.getCode(), close.getReason());
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " CLOSE method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    private void onContinuation(Frame frame, Callback callback)
    {
        acceptMessage(frame, callback);
    }

    private void onPing(Frame frame, Callback callback)
    {
        if (pingHandle != null)
        {
            try
            {
                ByteBuffer payload = frame.getPayload();
                if (payload == null)
                    payload = BufferUtil.EMPTY_BUFFER;

                pingHandle.invoke(payload);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getName() + " PING method error: " + cause.getMessage(), cause);
            }
        }
        callback.succeeded();
    }

    private void onPong(Frame frame, Callback callback)
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

    private void onText(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = textSink;

        acceptMessage(frame, callback);
    }
}

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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.invoke.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.BadPayloadException;
import org.eclipse.jetty.websocket.core.CloseException;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.UpgradeException;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class JettyWebSocketFrameHandler implements FrameHandler
{
    private final Logger log;
    private final Executor executor;
    private final Object endpointInstance;
    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private MethodHandle textHandle;
    private final Class<? extends MessageSink> textSinkClass;
    private MethodHandle binaryHandle;
    private final Class<? extends MessageSink> binarySinkClass;
    private MethodHandle frameHandle;
    private MethodHandle pingHandle;
    private MethodHandle pongHandle;
    /**
     * Immutable HandshakeRequest available via Session
     */
    private final UpgradeRequest upgradeRequest;
    /**
     * Immutable HandshakeResponse available via Session
     */
    private final UpgradeResponse upgradeResponse;
    private final CompletableFuture<Session> futureSession;
    private final Customizer customizer;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private WebSocketSessionImpl session;

    public JettyWebSocketFrameHandler(Executor executor,
        Object endpointInstance,
        UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse,
        MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
        MethodHandle textHandle, MethodHandle binaryHandle,
        Class<? extends MessageSink> textSinkClass,
        Class<? extends MessageSink> binarySinkClass,
        MethodHandle frameHandle,
        MethodHandle pingHandle, MethodHandle pongHandle,
        CompletableFuture<Session> futureSession,
        Customizer customizer)
    {
        this.log = Log.getLogger(endpointInstance.getClass());

        this.executor = executor;
        this.endpointInstance = endpointInstance;
        this.upgradeRequest = upgradeRequest;
        this.upgradeResponse = upgradeResponse;

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

        this.customizer = customizer;
    }

    public WebSocketSessionImpl getSession()
    {
        return session;
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        // TODO: FrameHandler cleanup?
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void onError(Throwable cause)
    {
        cause = convertCause(cause);
        futureSession.completeExceptionally(cause);

        if (errorHandle == null)
        {
            log.warn("Unhandled Error: Endpoint " + endpointInstance.getClass().getName() + " : " + cause);
            if (log.isDebugEnabled())
                log.debug("unhandled", cause);
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

    public static Throwable convertCause(Throwable cause)
    {
        if (cause instanceof MessageTooLargeException)
            return new org.eclipse.jetty.websocket.api.MessageTooLargeException(cause.getMessage(), cause);

        if (cause instanceof ProtocolException)
            return new org.eclipse.jetty.websocket.api.ProtocolException(cause.getMessage(), cause);

        if (cause instanceof BadPayloadException)
            return new org.eclipse.jetty.websocket.api.BadPayloadException(cause.getMessage(), cause);

        if (cause instanceof CloseException)
            return new org.eclipse.jetty.websocket.api.CloseException(((CloseException)cause).getStatusCode(), cause.getMessage(), cause);

        if (cause instanceof WebSocketTimeoutException)
            return new org.eclipse.jetty.websocket.api.WebSocketTimeoutException(cause.getMessage(), cause);

        if (cause instanceof InvalidSignatureException)
            return new org.eclipse.jetty.websocket.api.InvalidWebSocketException(cause.getMessage(), cause);

        if (cause instanceof UpgradeException)
            return new org.eclipse.jetty.websocket.api.UpgradeException(((UpgradeException)cause).getRequestURI(), cause);

        return cause;
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        // Send to raw frame handling on user side (eg: WebSocketFrameListener)
        if (frameHandle != null)
        {
            try
            {
                frameHandle.invoke(new JettyWebSocketFrame(frame));
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
    public void onOpen(CoreSession coreSession)
    {
        customizer.customize(coreSession);

        session = new WebSocketSessionImpl(coreSession, this, upgradeRequest, upgradeResponse);

        frameHandle = JettyWebSocketFrameHandlerFactory.bindTo(frameHandle, session);
        openHandle = JettyWebSocketFrameHandlerFactory.bindTo(openHandle, session);
        closeHandle = JettyWebSocketFrameHandlerFactory.bindTo(closeHandle, session);
        errorHandle = JettyWebSocketFrameHandlerFactory.bindTo(errorHandle, session);
        textHandle = JettyWebSocketFrameHandlerFactory.bindTo(textHandle, session);
        binaryHandle = JettyWebSocketFrameHandlerFactory.bindTo(binaryHandle, session);
        pingHandle = JettyWebSocketFrameHandlerFactory.bindTo(pingHandle, session);
        pongHandle = JettyWebSocketFrameHandlerFactory.bindTo(pongHandle, session);

        if (textHandle != null)
            textSink = JettyWebSocketFrameHandlerFactory.createMessageSink(textHandle, textSinkClass, executor, coreSession.getMaxTextMessageSize());

        if (binaryHandle != null)
            binarySink = JettyWebSocketFrameHandlerFactory.createMessageSink(binaryHandle, binarySinkClass, executor, coreSession.getMaxBinaryMessageSize());

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
                CloseStatus close = new CloseStatus(frame.getPayload());
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
        else
        {
            // Automatically respond
            Frame pong = new Frame(OpCode.PONG);
            if (frame.hasPayload())
                pong.setPayload(frame.getPayload());
            getSession().getRemote().getCoreSession().sendFrame(pong, Callback.NOOP, false);
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

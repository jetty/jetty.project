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
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.CloseException;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.exception.UpgradeException;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;
import org.eclipse.jetty.websocket.core.messages.MessageSink;
import org.eclipse.jetty.websocket.core.util.InvokerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketFrameHandler implements FrameHandler
{
    private final AtomicBoolean closeNotified = new AtomicBoolean();
    private final Logger log;
    private final WebSocketContainer container;
    private final Object endpointInstance;
    private final JettyWebSocketFrameHandlerMetadata metadata;
    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private MethodHandle textHandle;
    private MethodHandle binaryHandle;
    private final Class<? extends MessageSink> textSinkClass;
    private final Class<? extends MessageSink> binarySinkClass;
    private MethodHandle frameHandle;
    private MethodHandle pingHandle;
    private MethodHandle pongHandle;
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private WebSocketSession session;

    public JettyWebSocketFrameHandler(WebSocketContainer container, Object endpointInstance, JettyWebSocketFrameHandlerMetadata metadata)
    {
        this.log = LoggerFactory.getLogger(endpointInstance.getClass());
        this.container = container;
        this.endpointInstance = endpointInstance;
        this.metadata = metadata;

        this.openHandle = InvokerUtils.bindTo(metadata.getOpenHandle(), endpointInstance);
        this.closeHandle = InvokerUtils.bindTo(metadata.getCloseHandle(), endpointInstance);
        this.errorHandle = InvokerUtils.bindTo(metadata.getErrorHandle(), endpointInstance);
        this.textHandle = InvokerUtils.bindTo(metadata.getTextHandle(), endpointInstance);
        this.binaryHandle = InvokerUtils.bindTo(metadata.getBinaryHandle(), endpointInstance);
        this.textSinkClass = metadata.getTextSink();
        this.binarySinkClass = metadata.getBinarySink();
        this.frameHandle = InvokerUtils.bindTo(metadata.getFrameHandle(), endpointInstance);
        this.pingHandle = InvokerUtils.bindTo(metadata.getPingHandle(), endpointInstance);
        this.pongHandle = InvokerUtils.bindTo(metadata.getPongHandle(), endpointInstance);
    }

    public void setUpgradeRequest(UpgradeRequest upgradeRequest)
    {
        this.upgradeRequest = upgradeRequest;
    }

    public void setUpgradeResponse(UpgradeResponse upgradeResponse)
    {
        this.upgradeResponse = upgradeResponse;
    }

    public UpgradeRequest getUpgradeRequest()
    {
        return upgradeRequest;
    }

    public UpgradeResponse getUpgradeResponse()
    {
        return upgradeResponse;
    }

    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        try
        {
            metadata.customize(coreSession);
            session = new WebSocketSession(container, coreSession, this);
            if (!session.isOpen())
                throw new IllegalStateException("Session is not open");

            frameHandle = InvokerUtils.bindTo(frameHandle, session);
            openHandle = InvokerUtils.bindTo(openHandle, session);
            closeHandle = InvokerUtils.bindTo(closeHandle, session);
            errorHandle = InvokerUtils.bindTo(errorHandle, session);
            textHandle = InvokerUtils.bindTo(textHandle, session);
            binaryHandle = InvokerUtils.bindTo(binaryHandle, session);
            pingHandle = InvokerUtils.bindTo(pingHandle, session);
            pongHandle = InvokerUtils.bindTo(pongHandle, session);

            if (textHandle != null)
                textSink = createMessageSink(textSinkClass, session, textHandle, isAutoDemand());

            if (binaryHandle != null)
                binarySink = createMessageSink(binarySinkClass, session, binaryHandle, isAutoDemand());

            if (openHandle != null)
                openHandle.invoke();

            if (session.isOpen())
                container.notifySessionListeners((listener) -> listener.onWebSocketSessionOpened(session));

            callback.succeeded();
        }
        catch (Throwable cause)
        {
            callback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " OPEN method error: " + cause.getMessage(), cause));
        }
        finally
        {
            autoDemand();
        }
    }

    private static MessageSink createMessageSink(Class<? extends MessageSink> sinkClass, WebSocketSession session, MethodHandle msgHandle, boolean autoDemanding)
    {
        if (msgHandle == null)
            return null;
        if (sinkClass == null)
            return null;

        try
        {
            MethodHandles.Lookup lookup = JettyWebSocketFrameHandlerFactory.getServerMethodHandleLookup();
            MethodHandle ctorHandle = lookup.findConstructor(sinkClass,
                MethodType.methodType(void.class, CoreSession.class, MethodHandle.class, boolean.class));
            return (MessageSink)ctorHandle.invoke(session.getCoreSession(), msgHandle, autoDemanding);
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException("Missing expected MessageSink constructor found at: " + sinkClass.getName(), e);
        }
        catch (IllegalAccessException | InstantiationException | InvocationTargetException e)
        {
            throw new RuntimeException("Unable to create MessageSink: " + sinkClass.getName(), e);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Throwable t)
        {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void onFrame(Frame frame, Callback coreCallback)
    {
        CompletableFuture<Void> frameCallback = null;
        if (frameHandle != null)
        {
            try
            {
                frameCallback = new org.eclipse.jetty.websocket.api.Callback.Completable();
                frameHandle.invoke(new JettyWebSocketFrame(frame), frameCallback);
            }
            catch (Throwable cause)
            {
                coreCallback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " FRAME method error: " + cause.getMessage(), cause));
                return;
            }
        }

        Callback.Completable eventCallback = new Callback.Completable();
        switch (frame.getOpCode())
        {
            case OpCode.CLOSE -> onCloseFrame(frame, eventCallback);
            case OpCode.PING -> onPingFrame(frame, eventCallback);
            case OpCode.PONG -> onPongFrame(frame, eventCallback);
            case OpCode.TEXT -> onTextFrame(frame, eventCallback);
            case OpCode.BINARY -> onBinaryFrame(frame, eventCallback);
            case OpCode.CONTINUATION -> onContinuationFrame(frame, eventCallback);
            default -> coreCallback.failed(new IllegalStateException());
        };

        // Combine the callback from the frame handler and the event handler.
        CompletableFuture<Void> callback = eventCallback;
        if (frameCallback != null)
            callback = frameCallback.thenCompose(ignored -> eventCallback);
        callback.whenComplete((r, x) ->
        {
            if (x == null)
                coreCallback.succeeded();
            else
                coreCallback.failed(x);
        });
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        try
        {
            cause = convertCause(cause);
            if (errorHandle != null)
            {
                errorHandle.invoke(cause);
            }
            else
            {
                if (log.isDebugEnabled())
                    log.debug("Unhandled Error: Endpoint {}", endpointInstance.getClass().getName(), cause);
                else
                    log.warn("Unhandled Error: Endpoint {} : {}", endpointInstance.getClass().getName(), cause.toString());
            }
            callback.succeeded();
        }
        catch (Throwable t)
        {
            WebSocketException wsError = new WebSocketException(endpointInstance.getClass().getSimpleName() + " ERROR method error: " + cause.getMessage(), t);
            wsError.addSuppressed(cause);
            callback.failed(wsError);
        }
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        notifyOnClose(closeStatus, callback);
        container.notifySessionListeners((listener) -> listener.onWebSocketSessionClosed(session));
    }

    private void onCloseFrame(Frame frame, Callback callback)
    {
        notifyOnClose(CloseStatus.getCloseStatus(frame), callback);
    }

    private void notifyOnClose(CloseStatus closeStatus, Callback callback)
    {
        // Make sure onClose is only notified once.
        if (!closeNotified.compareAndSet(false, true))
        {
            callback.failed(new ClosedChannelException());
            return;
        }

        try
        {
            if (closeHandle != null)
                closeHandle.invoke(closeStatus.getCode(), closeStatus.getReason());
            callback.succeeded();
        }
        catch (Throwable cause)
        {
            callback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " CLOSE method error: " + cause.getMessage(), cause));
        }
    }

    private void onPingFrame(Frame frame, Callback callback)
    {
        if (pingHandle != null)
        {
            try
            {
                ByteBuffer payload = frame.getPayload();
                if (payload == null)
                    payload = BufferUtil.EMPTY_BUFFER;
                else
                    payload = BufferUtil.copy(payload);
                pingHandle.invoke(payload);
                callback.succeeded();
                autoDemand();
            }
            catch (Throwable cause)
            {
                callback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " PING method error: " + cause.getMessage(), cause));
            }
        }
        else
        {
            // Automatically respond.
            getSession().sendPong(frame.getPayload(), new org.eclipse.jetty.websocket.api.Callback()
            {
                @Override
                public void succeed()
                {
                    callback.succeeded();
                    autoDemand();
                }

                @Override
                public void fail(Throwable x)
                {
                    // Ignore failures, we might be output closed and receive a PING.
                    callback.succeeded();
                }
            });
        }
    }

    private void onPongFrame(Frame frame, Callback callback)
    {
        if (pongHandle != null)
        {
            try
            {
                ByteBuffer payload = frame.getPayload();
                if (payload == null)
                    payload = BufferUtil.EMPTY_BUFFER;
                else
                    payload = BufferUtil.copy(payload);
                pongHandle.invoke(payload);
                callback.succeeded();
                autoDemand();
            }
            catch (Throwable cause)
            {
                callback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " PONG method error: " + cause.getMessage(), cause));
            }
        }
        else
        {
            autoDemand();
        }
    }

    private void onTextFrame(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = textSink;
        acceptFrame(frame, callback);
    }

    private void onBinaryFrame(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = binarySink;
        acceptFrame(frame, callback);
    }

    private void onContinuationFrame(Frame frame, Callback callback)
    {
        acceptFrame(frame, callback);
    }

    private void acceptFrame(Frame frame, Callback callback)
    {
        // No message sink is active.
        if (activeMessageSink == null)
        {
            callback.succeeded();
            autoDemand();
            return;
        }

        // Accept the payload into the message sink.
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    boolean isAutoDemand()
    {
        return metadata.isAutoDemand();
    }

    private void autoDemand()
    {
        if (isAutoDemand())
            session.getCoreSession().demand(1);
    }

    public String toString()
    {
        return String.format("%s@%x[%s]", this.getClass().getSimpleName(), this.hashCode(), endpointInstance.getClass().getName());
    }

    public static Throwable convertCause(Throwable cause)
    {
        if (cause instanceof MessageTooLargeException)
            return new org.eclipse.jetty.websocket.api.exceptions.MessageTooLargeException(cause.getMessage(), cause);

        if (cause instanceof ProtocolException)
            return new org.eclipse.jetty.websocket.api.exceptions.ProtocolException(cause.getMessage(), cause);

        if (cause instanceof BadPayloadException)
            return new org.eclipse.jetty.websocket.api.exceptions.BadPayloadException(cause.getMessage(), cause);

        if (cause instanceof CloseException)
            return new org.eclipse.jetty.websocket.api.exceptions.CloseException(((CloseException)cause).getStatusCode(), cause.getMessage(), cause);

        if (cause instanceof WebSocketTimeoutException)
            return new org.eclipse.jetty.websocket.api.exceptions.WebSocketTimeoutException(cause.getMessage(), cause);

        if (cause instanceof InvalidSignatureException)
            return new org.eclipse.jetty.websocket.api.exceptions.InvalidWebSocketException(cause.getMessage(), cause);

        if (cause instanceof UpgradeException ue)
            return new org.eclipse.jetty.websocket.api.exceptions.UpgradeException(ue.getRequestURI(), ue.getResponseStatusCode(), cause);

        return cause;
    }
}

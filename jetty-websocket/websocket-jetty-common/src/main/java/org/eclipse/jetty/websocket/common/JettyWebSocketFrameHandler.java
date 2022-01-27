//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Configuration;
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
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.core.internal.util.InvokerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JettyWebSocketFrameHandler implements FrameHandler
{
    private enum SuspendState
    {
        DEMANDING,
        SUSPENDING,
        SUSPENDED,
        CLOSED
    }

    private final AutoLock lock = new AutoLock();
    private final Logger log;
    private final WebSocketContainer container;
    private final Object endpointInstance;
    private final BatchMode batchMode;
    private final AtomicBoolean closeNotified = new AtomicBoolean();
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
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;

    private final Configuration.Customizer customizer;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private WebSocketSession session;
    private SuspendState state = SuspendState.DEMANDING;
    private Runnable delayedOnFrame;
    private CoreSession coreSession;

    public JettyWebSocketFrameHandler(WebSocketContainer container,
                                      Object endpointInstance,
                                      MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      MethodHandle textHandle, MethodHandle binaryHandle,
                                      Class<? extends MessageSink> textSinkClass,
                                      Class<? extends MessageSink> binarySinkClass,
                                      MethodHandle frameHandle,
                                      MethodHandle pingHandle, MethodHandle pongHandle,
                                      BatchMode batchMode,
                                      Configuration.Customizer customizer)
    {
        this.log = LoggerFactory.getLogger(endpointInstance.getClass());

        this.container = container;
        this.endpointInstance = endpointInstance;

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

        this.batchMode = batchMode;
        this.customizer = customizer;
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

    public BatchMode getBatchMode()
    {
        return batchMode;
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
            customizer.customize(coreSession);
            this.coreSession = coreSession;
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

            Executor executor = container.getExecutor();

            if (textHandle != null)
                textSink = JettyWebSocketFrameHandlerFactory.createMessageSink(textHandle, textSinkClass, executor, session);

            if (binaryHandle != null)
                binarySink = JettyWebSocketFrameHandlerFactory.createMessageSink(binaryHandle, binarySinkClass, executor, session);

            if (openHandle != null)
                openHandle.invoke();

            if (session.isOpen())
                container.notifySessionListeners((listener) -> listener.onWebSocketSessionOpened(session));

            callback.succeeded();
            demand();
        }
        catch (Throwable cause)
        {
            callback.failed(new WebSocketException(endpointInstance.getClass().getSimpleName() + " OPEN method error: " + cause.getMessage(), cause));
        }
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        try (AutoLock l = lock.lock())
        {
            switch (state)
            {
                case DEMANDING:
                    break;

                case SUSPENDING:
                    delayedOnFrame = () -> onFrame(frame, callback);
                    state = SuspendState.SUSPENDED;
                    return;

                default:
                    throw new IllegalStateException();
            }

            // If we have received a close frame, set state to closed to disallow further suspends and resumes.
            if (frame.getOpCode() == OpCode.CLOSE)
                state = SuspendState.CLOSED;
        }

        // Send to raw frame handling on user side (eg: WebSocketFrameListener)
        if (frameHandle != null)
        {
            try
            {
                frameHandle.invoke(new JettyWebSocketFrame(frame));
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getSimpleName() + " FRAME method error: " + cause.getMessage(), cause);
            }
        }

        switch (frame.getOpCode())
        {
            case OpCode.CLOSE:
                onCloseFrame(frame, callback);
                break;
            case OpCode.PING:
                onPingFrame(frame, callback);
                break;
            case OpCode.PONG:
                onPongFrame(frame, callback);
                break;
            case OpCode.TEXT:
                onTextFrame(frame, callback);
                break;
            case OpCode.BINARY:
                onBinaryFrame(frame, callback);
                break;
            case OpCode.CONTINUATION:
                onContinuationFrame(frame, callback);
                break;
            default:
                callback.failed(new IllegalStateException());
        }
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        try
        {
            cause = convertCause(cause);
            if (errorHandle != null)
                errorHandle.invoke(cause);
            else
            {
                if (log.isDebugEnabled())
                    log.warn("Unhandled Error: Endpoint {}", endpointInstance.getClass().getName(), cause);
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

    private void onCloseFrame(Frame frame, Callback callback)
    {
        notifyOnClose(CloseStatus.getCloseStatus(frame), callback);
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        try (AutoLock l = lock.lock())
        {
            // We are now closed and cannot suspend or resume.
            state = SuspendState.CLOSED;
        }

        notifyOnClose(closeStatus, callback);
        container.notifySessionListeners((listener) -> listener.onWebSocketSessionClosed(session));
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

    public String toString()
    {
        return String.format("%s@%x[%s]", this.getClass().getSimpleName(), this.hashCode(), endpointInstance.getClass().getName());
    }

    private void acceptMessage(Frame frame, Callback callback)
    {
        // No message sink is active
        if (activeMessageSink == null)
        {
            callback.succeeded();
            demand();
            return;
        }

        // Accept the payload into the message sink
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }

    private void onBinaryFrame(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = binarySink;

        acceptMessage(frame, callback);
    }

    private void onContinuationFrame(Frame frame, Callback callback)
    {
        acceptMessage(frame, callback);
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

                pingHandle.invoke(payload);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getSimpleName() + " PING method error: " + cause.getMessage(), cause);
            }
        }
        else
        {
            // Automatically respond
            ByteBuffer payload = BufferUtil.copy(frame.getPayload());
            getSession().getRemote().sendPong(payload, WriteCallback.NOOP);
        }

        callback.succeeded();
        demand();
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

                pongHandle.invoke(payload);
            }
            catch (Throwable cause)
            {
                throw new WebSocketException(endpointInstance.getClass().getSimpleName() + " PONG method error: " + cause.getMessage(), cause);
            }
        }

        callback.succeeded();
        demand();
    }

    private void onTextFrame(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = textSink;

        acceptMessage(frame, callback);
    }

    @Override
    public boolean isDemanding()
    {
        return true;
    }

    public void suspend()
    {
        try (AutoLock l = lock.lock())
        {
            switch (state)
            {
                case DEMANDING:
                    state = SuspendState.SUSPENDING;
                    break;

                default:
                    throw new IllegalStateException(state.name());
            }
        }
    }

    public void resume()
    {
        boolean needDemand = false;
        Runnable delayedFrame = null;
        try (AutoLock l = lock.lock())
        {
            switch (state)
            {
                case DEMANDING:
                    throw new IllegalStateException("Already Resumed");

                case SUSPENDED:
                    needDemand = true;
                    delayedFrame = delayedOnFrame;
                    delayedOnFrame = null;
                    state = SuspendState.DEMANDING;
                    break;

                case SUSPENDING:
                    if (delayedOnFrame != null)
                        throw new IllegalStateException();
                    state = SuspendState.DEMANDING;
                    break;

                default:
                    throw new IllegalStateException(state.name());
            }
        }

        if (needDemand)
        {
            if (delayedFrame != null)
                delayedFrame.run();
            else
                session.getCoreSession().demand(1);
        }
    }

    private void demand()
    {
        boolean demand = false;
        try (AutoLock l = lock.lock())
        {
            switch (state)
            {
                case DEMANDING:
                    demand = true;
                    break;

                case SUSPENDING:
                    state = SuspendState.SUSPENDED;
                    break;

                default:
                    throw new IllegalStateException(state.name());
            }
        }

        if (demand)
            session.getCoreSession().demand(1);
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

        if (cause instanceof UpgradeException)
        {
            UpgradeException ue = (UpgradeException)cause;
            return new org.eclipse.jetty.websocket.api.exceptions.UpgradeException(ue.getRequestURI(), ue.getResponseStatusCode(), cause);
        }

        return cause;
    }
}

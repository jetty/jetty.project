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

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.ee9.websocket.api.BatchMode;
import org.eclipse.jetty.ee9.websocket.api.UpgradeRequest;
import org.eclipse.jetty.ee9.websocket.api.UpgradeResponse;
import org.eclipse.jetty.ee9.websocket.api.WebSocketContainer;
import org.eclipse.jetty.ee9.websocket.api.WriteCallback;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
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
import org.eclipse.jetty.websocket.core.messages.MessageSink;
import org.eclipse.jetty.websocket.core.util.InvokerUtils;
import org.eclipse.jetty.websocket.core.util.MethodHolder;
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
    private MethodHolder openHandle;
    private MethodHolder closeHandle;
    private MethodHolder errorHandle;
    private MethodHolder textHandle;
    private final Class<? extends MessageSink> textSinkClass;
    private MethodHolder binaryHandle;
    private final Class<? extends MessageSink> binarySinkClass;
    private MethodHolder frameHandle;
    private MethodHolder pingHandle;
    private MethodHolder pongHandle;
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;

    private final Configuration.Customizer customizer;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private WebSocketSession session;
    private SuspendState state = SuspendState.DEMANDING;
    private Frame delayedFrame;
    private Callback delayedCallback;

    public JettyWebSocketFrameHandler(WebSocketContainer container,
                                      Object endpointInstance,
                                      MethodHolder openHandle, MethodHolder closeHandle, MethodHolder errorHandle,
                                      MethodHolder textHandle, MethodHolder binaryHandle,
                                      Class<? extends MessageSink> textSinkClass,
                                      Class<? extends MessageSink> binarySinkClass,
                                      MethodHolder frameHandle,
                                      MethodHolder pingHandle, MethodHolder pongHandle,
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

            Executor executor = coreSession.getWebSocketComponents().getExecutor();
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
        try (AutoLock ignored = lock.lock())
        {
            switch (state)
            {
                case DEMANDING:
                    break;

                case SUSPENDING:
                    assert (delayedFrame == null && delayedCallback == null);
                    delayedFrame = frame;
                    delayedCallback = callback;
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
            case OpCode.CLOSE -> onCloseFrame(frame, callback);
            case OpCode.PING -> onPingFrame(frame, callback);
            case OpCode.PONG -> onPongFrame(frame, callback);
            case OpCode.TEXT -> onTextFrame(frame, callback);
            case OpCode.BINARY -> onBinaryFrame(frame, callback);
            case OpCode.CONTINUATION -> onContinuationFrame(frame, callback);
            default -> callback.failed(new IllegalStateException());
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
        Callback delayedCallback;
        try (AutoLock ignored = lock.lock())
        {
            // We are now closed and cannot suspend or resume.
            state = SuspendState.CLOSED;
            this.delayedFrame = null;
            delayedCallback = this.delayedCallback;
            this.delayedCallback = null;
        }

        CloseException closeException = new CloseException(closeStatus.getCode(), closeStatus.getCause());
        if (delayedCallback != null)
            delayedCallback.failed(closeException);

        if (textSink != null)
            textSink.fail(closeException);
        if (binarySink != null)
            binarySink.fail(closeException);

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
        MessageSink messageSink = activeMessageSink;
        if (frame.isFin())
            activeMessageSink = null;
        messageSink.accept(frame, callback);
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

            callback.succeeded();
            demand();
        }
        else
        {
            // Automatically respond.
            getSession().getRemote().sendPong(frame.getPayload(), new WriteCallback()
            {
                @Override
                public void writeSuccess()
                {
                    callback.succeeded();
                    demand();
                }

                @Override
                public void writeFailed(Throwable x)
                {
                    // Ignore failures, we might be output closed and receive ping.
                    callback.succeeded();
                    demand();
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

    public void suspend()
    {
        try (AutoLock ignored = lock.lock())
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
        Frame frame = null;
        Callback callback = null;
        try (AutoLock ignored = lock.lock())
        {
            switch (state)
            {
                case DEMANDING:
                    throw new IllegalStateException("Already Resumed");

                case SUSPENDED:
                    needDemand = true;
                    frame = delayedFrame;
                    callback = delayedCallback;
                    delayedFrame = null;
                    delayedCallback = null;
                    state = SuspendState.DEMANDING;
                    break;

                case SUSPENDING:
                    if (delayedFrame != null)
                        throw new IllegalStateException();
                    state = SuspendState.DEMANDING;
                    break;

                default:
                    throw new IllegalStateException(state.name());
            }
        }

        if (needDemand)
        {
            if (frame != null)
                onFrame(frame, callback);
            else
                session.getCoreSession().demand();
        }
    }

    private void demand()
    {
        boolean demand = false;
        try (AutoLock ignored = lock.lock())
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
            session.getCoreSession().demand();
    }

    public static Throwable convertCause(Throwable cause)
    {
        if (cause instanceof MessageTooLargeException)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.MessageTooLargeException(cause.getMessage(), cause);

        if (cause instanceof ProtocolException)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.ProtocolException(cause.getMessage(), cause);

        if (cause instanceof BadPayloadException)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.BadPayloadException(cause.getMessage(), cause);

        if (cause instanceof CloseException ce)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.CloseException(ce.getStatusCode(), cause.getMessage(), cause);

        if (cause instanceof WebSocketTimeoutException)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.WebSocketTimeoutException(cause.getMessage(), cause);

        if (cause instanceof InvalidSignatureException)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.InvalidWebSocketException(cause.getMessage(), cause);

        if (cause instanceof UpgradeException ue)
            return new org.eclipse.jetty.ee9.websocket.api.exceptions.UpgradeException(ue.getRequestURI(), ue.getResponseStatusCode(), cause);

        return cause;
    }
}

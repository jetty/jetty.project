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
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
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

public class JettyWebSocketFrameHandler implements FrameHandler
{
    private enum SuspendState
    {
        DEMANDING,
        SUSPENDING,
        SUSPENDED
    }

    private final Logger log;
    private final WebSocketContainer container;
    private final Object endpointInstance;
    private final BatchMode batchMode;
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

    private final Customizer customizer;
    private MessageSink textSink;
    private MessageSink binarySink;
    private MessageSink activeMessageSink;
    private WebSocketSession session;
    private SuspendState state = SuspendState.DEMANDING;
    private Runnable delayedOnFrame;

    public JettyWebSocketFrameHandler(WebSocketContainer container,
                                      Object endpointInstance,
                                      MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                                      MethodHandle textHandle, MethodHandle binaryHandle,
                                      Class<? extends MessageSink> textSinkClass,
                                      Class<? extends MessageSink> binarySinkClass,
                                      MethodHandle frameHandle,
                                      MethodHandle pingHandle, MethodHandle pongHandle,
                                      BatchMode batchMode,
                                      Customizer customizer)
    {
        this.log = Log.getLogger(endpointInstance.getClass());

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
            session = new WebSocketSession(coreSession, this);

            frameHandle = JettyWebSocketFrameHandlerFactory.bindTo(frameHandle, session);
            openHandle = JettyWebSocketFrameHandlerFactory.bindTo(openHandle, session);
            closeHandle = JettyWebSocketFrameHandlerFactory.bindTo(closeHandle, session);
            errorHandle = JettyWebSocketFrameHandlerFactory.bindTo(errorHandle, session);
            textHandle = JettyWebSocketFrameHandlerFactory.bindTo(textHandle, session);
            binaryHandle = JettyWebSocketFrameHandlerFactory.bindTo(binaryHandle, session);
            pingHandle = JettyWebSocketFrameHandlerFactory.bindTo(pingHandle, session);
            pongHandle = JettyWebSocketFrameHandlerFactory.bindTo(pongHandle, session);

            Executor executor = container.getExecutor();

            if (textHandle != null)
                textSink = JettyWebSocketFrameHandlerFactory.createMessageSink(textHandle, textSinkClass, executor, session);

            if (binaryHandle != null)
                binarySink = JettyWebSocketFrameHandlerFactory.createMessageSink(binaryHandle, binarySinkClass, executor, session);

            if (openHandle != null)
                openHandle.invoke();

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
        synchronized (this)
        {
            switch (state)
            {
                case DEMANDING:
                    break;

                case SUSPENDING:
                    delayedOnFrame = () -> onFrame(frame, callback);
                    state = SuspendState.SUSPENDED;
                    return;

                case SUSPENDED:
                default:
                    throw new IllegalStateException();
            }
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

        // Demand after succeeding any received frame
        Callback demandingCallback = Callback.from(() ->
            {
                try
                {
                    demand();
                }
                catch (Throwable t)
                {
                    callback.failed(t);
                    return;
                }

                callback.succeeded();
            },
            callback::failed
        );

        switch (frame.getOpCode())
        {
            case OpCode.CLOSE:
                onCloseFrame(frame, callback);
                break;
            case OpCode.PING:
                onPingFrame(frame, demandingCallback);
                break;
            case OpCode.PONG:
                onPongFrame(frame, demandingCallback);
                break;
            case OpCode.TEXT:
                onTextFrame(frame, demandingCallback);
                break;
            case OpCode.BINARY:
                onBinaryFrame(frame, demandingCallback);
                break;
            case OpCode.CONTINUATION:
                onContinuationFrame(frame, demandingCallback);
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
                log.warn("Unhandled Error: Endpoint " + endpointInstance.getClass().getName() + " : " + cause);
                if (log.isDebugEnabled())
                    log.debug("unhandled", cause);
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

        container.notifySessionListeners((listener) -> listener.onWebSocketSessionClosed(session));
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

    private void onCloseFrame(Frame frame, Callback callback)
    {
        callback.succeeded();
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
            Frame pong = new Frame(OpCode.PONG);
            if (frame.hasPayload())
                pong.setPayload(frame.getPayload());
            getSession().getRemote().getCoreSession().sendFrame(pong, Callback.NOOP, false);
        }
        callback.succeeded();
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
        synchronized (this)
        {
            switch (state)
            {
                case DEMANDING:
                    state = SuspendState.SUSPENDING;
                    break;

                case SUSPENDED:
                case SUSPENDING:
                    throw new IllegalStateException("Already Suspended");

                default:
                    throw new IllegalStateException();
            }
        }
    }

    public void resume()
    {
        Runnable delayedFrame = null;
        synchronized (this)
        {
            switch (state)
            {
                case DEMANDING:
                    throw new IllegalStateException("Already Resumed");

                case SUSPENDED:
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
                    throw new IllegalStateException();
            }
        }

        if (delayedFrame != null)
            delayedFrame.run();
        else
            session.getCoreSession().demand(1);
    }

    private void demand()
    {
        boolean demand = false;
        synchronized (this)
        {
            switch (state)
            {
                case DEMANDING:
                    demand = true;
                    break;

                case SUSPENDED:
                    throw new IllegalStateException("Suspended");

                case SUSPENDING:
                    state = SuspendState.SUSPENDED;
                    break;

                default:
                    throw new IllegalStateException();
            }
        }

        if (demand)
            session.getCoreSession().demand(1);
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
        {
            UpgradeException ue = (UpgradeException)cause;
            return new org.eclipse.jetty.websocket.api.UpgradeException(ue.getRequestURI(), ue.getResponseStatusCode(), cause);
        }

        return cause;
    }
}

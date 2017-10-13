//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import static org.eclipse.jetty.websocket.core.WebSocketSessionState.State;
import static org.eclipse.jetty.websocket.core.WebSocketSessionState.State.CLOSED;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;

/**
 * The Core WebSocket Session.
 *
 */
public class WebSocketCoreSession extends ContainerLifeCycle implements IncomingFrames
{
    private Logger LOG = Log.getLogger(this.getClass());

    private final WebSocketSessionState state = new WebSocketSessionState();
    private final WebSocketPolicy policy;
    private final WebSocketLocalEndpoint localEndpoint;
    private final WebSocketRemoteEndpoint remoteEndpoint;
    private final ExtensionStack extensionStack;
    private final List<Listener> listeners = new ArrayList<>();

    private WebSocketCoreConnection connection;

    private final AtomicBoolean closeNotified = new AtomicBoolean(false);
    // Holder for errors during onOpen that are reported in doStart later
    private AtomicReference<Throwable> pendingError = new AtomicReference<>();

    public WebSocketCoreSession(WebSocketLocalEndpoint localEndpoint,
                                WebSocketRemoteEndpoint remoteEndpoint,
                                WebSocketPolicy policy,
                                ExtensionStack extensionStack)
    {
        this.localEndpoint = localEndpoint;
        this.remoteEndpoint = remoteEndpoint;
        this.policy = policy;
        this.extensionStack = extensionStack;
        addBean(extensionStack,true);
        extensionStack.setNextIncoming(new IncomingState());
        extensionStack.setNextOutgoing(new OutgoingState());
        addBean(this.localEndpoint, true);
        addBean(this.remoteEndpoint, true);
    }

    public ExtensionStack getExtensionStack()
    {
        return extensionStack;
    }

    public WebSocketRemoteEndpoint getRemote()
    {
        return remoteEndpoint;
    }

    public WebSocketLocalEndpoint getLocal()
    {
        return localEndpoint;
    }

    public void setWebSocketConnection(WebSocketCoreConnection connection)
    {
        this.connection = connection;
    }

    public void addSessionListener(Listener listener)
    {
        listeners.add(listener);
    }

    public void close(int statusCode, String reason, Callback callback)
    {
        getRemote().sendClose(statusCode, reason, callback);
    }

    public void close(CloseStatus closeStatus, Callback callback)
    {
        getRemote().sendClose(closeStatus.getCode(), closeStatus.getReason(), callback);
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    /**
     * Process an Error event seen by the Session and/or Connection
     *
     * @param cause the cause
     */
    public void processError(Throwable cause)
    {
        synchronized (pendingError)
        {
            if (!state.isOpen())
            {
                // this is a *really* fast fail, before the Session has even started.
                pendingError.compareAndSet(null, cause);
                return;
            }
        }

        // Forward Errors to Local WebSocket EndPoint
        localEndpoint.onError(cause);

        if (cause instanceof Utf8Appendable.NotUtf8Exception)
        {
            close(WebSocketConstants.BAD_PAYLOAD, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof SocketTimeoutException)
        {
            // A path often seen in Windows
            close(WebSocketConstants.SHUTDOWN, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof IOException)
        {
            close(WebSocketConstants.PROTOCOL, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof SocketException)
        {
            // A path unique to Unix
            close(WebSocketConstants.SHUTDOWN, cause.getMessage(), Callback.NOOP);
        }
        else if (cause instanceof CloseException)
        {
            CloseException ce = (CloseException) cause;
            Callback callback = Callback.NOOP;

            // Force disconnect for protocol breaking status codes
            switch (ce.getStatusCode())
            {
                case WebSocketConstants.PROTOCOL:
                case WebSocketConstants.BAD_DATA:
                case WebSocketConstants.BAD_PAYLOAD:
                case WebSocketConstants.MESSAGE_TOO_LARGE:
                case WebSocketConstants.POLICY_VIOLATION:
                case WebSocketConstants.SERVER_ERROR:
                {
                    callback = Callback.NOOP;
                }
            }

            close(ce.getStatusCode(), ce.getMessage(), callback);
        }
        else if (cause instanceof WebSocketTimeoutException)
        {
            close(WebSocketConstants.SHUTDOWN, cause.getMessage(), Callback.NOOP);
        }
        else
        {
            LOG.warn("Unhandled Error (closing connection)", cause);

            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = WebSocketConstants.SERVER_ERROR;
            if (getPolicy().getBehavior() == WebSocketBehavior.CLIENT)
            {
                statusCode = WebSocketConstants.POLICY_VIOLATION;
            }
            close(statusCode, cause.getMessage(), Callback.NOOP);
        }
    }

    /**
     * Open/Activate the session.
     */
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.onOpen()", this.getClass().getSimpleName());

        try
        {
            start();

            // Upgrade success
            state.onConnected();

            if (LOG.isDebugEnabled())
                LOG.debug("ConnectionState: Transition to CONNECTED");

            // Connect remoteEndpoint
            if (LOG.isDebugEnabled())
                LOG.debug("{}.onOpen() remoteEndpoint={}", this.getClass().getSimpleName(), remoteEndpoint);

            try
            {
                // Open WebSocket
                // Session is about to be opened (used by CDI and JSR356 Decoder init)
                notifySessionListeners((listener)-> listener.onInit(this));
                localEndpoint.onOpen();

                // Open connection
                state.onOpen();
                // Session is now Open (used by container APIs for session tracking)
                notifySessionListeners((listener)-> listener.onOpened(this));
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to OPEN");
            }
            catch (Throwable t)
            {
                LOG.warn("Error during OPEN", t);
                processError(new CloseException(WebSocketConstants.SERVER_ERROR, t));
            }

            /* Perform fillInterested outside of OPENING attempt.
             *
             * State transition of CONNECTING -> CONNECTED -> OPENING -> CLOSING (not OPEN)
             *
             * This is to allow for 2 specific scenarios.
             *
             * 1) Fast Close
             *    When an end user's WSEndpoint.onOpen() is called.
             *    That method calls session.close() method, or
             *    session.getRemote().sendClose()
             * 2) Fast Fail
             *    When an end users WSEndpoint.onOpen() is called.
             *    That method throws an (unhandled) Throwable.
             */

            // TODO what if we are going to start without read interest?  (eg reactive stream???)
            connection.fillInterested();
        }
        catch (Throwable t)
        {
            processError(t); // Handle error
        }
    }

    public WebSocketCoreConnection getConnection()
    {
        return this.connection;
    }

    public Executor getExecutor()
    {
        return this.connection.getExecutor();
    }

    public ByteBufferPool getBufferPool()
    {
        return this.connection.getBufferPool();
    }

    public void notifyClose(CloseStatus closeStatus)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("notifyClose({}) closeNotified={}", closeStatus, closeNotified.get());
        }

        // only notify once
        if (closeNotified.compareAndSet(false, true))
        {
            localEndpoint.onClose(closeStatus);
        }

        // Session is officially closed / handshake completed (CDI and API Containers use this)
        // TODO: this should be somewhere else, more centralized, so that even abnormal (non frame) closes can trigger it
        notifySessionListeners((listener)-> listener.onClosed(WebSocketCoreSession.this));

    }

    public void notifySessionListeners(Consumer<Listener> consumer)
    {
        for (Listener listener : listeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        Throwable pending = pendingError.get();
        if (pending != null)
        {
            processError(pending);
        }
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        this.connection.disconnect();
        super.doStop();
    }

    @Override
    public void incomingFrame(Frame frame, Callback callback)
    {
        extensionStack.incomingFrame(frame, callback);
    }

    interface Listener
    {
        /**
         * When the session is about to go into service (step before OPENING/OPEN)
         * @param session the session about to go into service.
         */
        void onInit(WebSocketCoreSession session);

        /**
         * When the session is officially OPEN.
         * @param session the session that is now OPEN.
         */
        void onOpened(WebSocketCoreSession session);

        /**
         * When the session is officially CLOSED.
         * @param session the session that is now CLOSED.
         */
        void onClosed(WebSocketCoreSession session);
    }

    private class IncomingState implements IncomingFrames
    {
        @Override
        public void incomingFrame(Frame frame, Callback callback)
        {
            try
            {
                State state = WebSocketCoreSession.this.state.get();
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("incomingFrame({}, {}) - connectionState={}, localEndpoint={}",
                              frame, callback, state, localEndpoint);
                }
                if (state != CLOSED)
                {
                    // For endpoints that want to see raw frames.
                    localEndpoint.onFrame(frame);

                    byte opcode = frame.getOpCode();
                    switch (opcode)
                    {
                        case OpCode.CLOSE:
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("ConnectionState: Close frame received");
                            CloseFrame closeframe = (CloseFrame)frame;
                            CloseStatus closeStatus = closeframe.getCloseStatus();
                            notifyClose(closeStatus);
                            close(closeStatus, Callback.NOOP);
                            
                            callback.succeeded();
                            return;
                        }
                        case OpCode.PING:
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("PING: {}", BufferUtil.toDetailString(frame.getPayload()));

                            ByteBuffer pongBuf;
                            if (frame.hasPayload())
                            {
                                pongBuf = ByteBuffer.allocate(frame.getPayload().remaining());
                                BufferUtil.put(frame.getPayload().slice(), pongBuf);
                                BufferUtil.flipToFlush(pongBuf, 0);
                            }
                            else
                            {
                                pongBuf = ByteBuffer.allocate(0);
                            }

                            localEndpoint.onPing(frame.getPayload());
                            callback.succeeded();

                            try
                            {
                                remoteEndpoint.sendPong(pongBuf, Callback.NOOP);
                            }
                            catch (Throwable t)
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Unable to send pong", t);
                            }
                            break;
                        }
                        case OpCode.PONG:
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("PONG: {}", BufferUtil.toDetailString(frame.getPayload()));

                            localEndpoint.onPong(frame.getPayload());
                            callback.succeeded();
                            break;
                        }
                        case OpCode.BINARY:
                        {
                            localEndpoint.onBinary(frame, callback);
                            // Let endpoint method handle callback
                            return;
                        }
                        case OpCode.TEXT:
                        {
                            localEndpoint.onText(frame, callback);
                            // Let endpoint method handle callback
                            return;
                        }
                        case OpCode.CONTINUATION:
                        {
                            localEndpoint.onContinuation(frame, callback);
                            // Let endpoint method handle callback
                            return;
                        }
                        default:
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Unhandled OpCode: {}", opcode);
                        }
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Discarding post EOF frame - {}", frame);
                }
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
        }
    }

    private class OutgoingState implements OutgoingFrames
    {
        @Override
        public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode)
        {
            if (frame instanceof CloseFrame)
            {
                if (!state.onClosing())
                {
                    callback.failed(new IOException("Already Closed or Closing"));
                    return;
                }
            }

            connection.outgoingFrame(frame,callback,batchMode);

        }
    }
}

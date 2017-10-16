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

import static org.eclipse.jetty.websocket.core.WebSocketChannelState.State;
import static org.eclipse.jetty.websocket.core.WebSocketChannelState.State.CLOSED;

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
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketConnection;

/**
 * The Core WebSocket Session.
 *
 */
public class WebSocketChannel extends ContainerLifeCycle implements IncomingFrames, OutgoingFrames
{
    private Logger LOG = Log.getLogger(this.getClass());

    private final WebSocketChannelState state = new WebSocketChannelState();
    private final WebSocketPolicy policy;
    private final FrameHandler handler;
    private final ExtensionStack extensionStack;
    private final String subprotocol;
    private final List<Listener> listeners = new ArrayList<>();

    private WebSocketConnection connection;

    private final AtomicBoolean closeNotified = new AtomicBoolean(false);
    // Holder for errors during onOpen that are reported in doStart later
    private AtomicReference<Throwable> pendingError = new AtomicReference<>();

    public WebSocketChannel(FrameHandler handler,
    		WebSocketPolicy policy,
    		ExtensionStack extensionStack,
    		String subprotocol)
    {
        this.handler = handler;
        this.policy = policy;
        this.extensionStack = extensionStack;
        this.subprotocol = subprotocol;
        addBean(extensionStack,true);
        extensionStack.setNextIncoming(new IncomingState());
        extensionStack.setNextOutgoing(new OutgoingState());
        addBean(handler, true);
    }

    public ExtensionStack getExtensionStack()
    {
        return extensionStack;
    }

    public FrameHandler getHandler()
    {
        return handler;
    }

    public String getSubprotocol()
    {
        return subprotocol;
    }
    
    public void setWebSocketConnection(WebSocketConnection connection)
    {
        this.connection = connection;
    }

    public void addSessionListener(Listener listener)
    {
        listeners.add(listener);
    }

    public void close(int statusCode, String reason, Callback callback)
    {
        outgoingFrame(new CloseFrame().setPayload(statusCode, reason), callback, BatchMode.OFF);
    }

    public void close(CloseStatus closeStatus, Callback callback)
    {
        close(closeStatus.getCode(), closeStatus.getReason(), callback);
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
        handler.onError(this,cause);

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
            LOG.debug("onOpen() {}", this);

        try
        {
            start();

            // Upgrade success
            state.onConnected();

            if (LOG.isDebugEnabled())
                LOG.debug("ConnectionState: Transition to CONNECTED");

            try
            {
                // Session is about to be opened (used by CDI and JSR356 Decoder init)
                notifySessionListeners((listener)-> listener.onInit(this));

                // Open connection
                state.onOpen();

                // Session is now Open (used by container APIs for session tracking)
                handler.onOpen(this);
                notifySessionListeners((listener)-> listener.onOpened(this));
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to OPEN");
            }
            catch (Throwable t)
            {
                LOG.warn("Error during OPEN", t);
                processError(new CloseException(WebSocketConstants.SERVER_ERROR, t));
            }

            // TODO what if we are going to start without read interest?  (eg reactive stream???)
            connection.fillInterested();
        }
        catch (Throwable t)
        {
            processError(t); // Handle error
        }
    }

    public WebSocketConnection getConnection()
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
            handler.onClose(this,closeStatus);
        }

        // Session is officially closed / handshake completed (CDI and API Containers use this)
        // TODO: this should be somewhere else, more centralized, so that even abnormal (non frame) closes can trigger it
        notifySessionListeners((listener)-> listener.onClosed(WebSocketChannel.this));

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

    @Override
    public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode) 
    {
        extensionStack.outgoingFrame(frame,callback,batchMode);
    }

    interface Listener
    {
        /**
         * When the session is about to go into service (step before OPENING/OPEN)
         * @param session the session about to go into service.
         */
        void onInit(WebSocketChannel session);

        /**
         * When the session is officially OPEN.
         * @param session the session that is now OPEN.
         */
        void onOpened(WebSocketChannel session);

        /**
         * When the session is officially CLOSED.
         * @param session the session that is now CLOSED.
         */
        void onClosed(WebSocketChannel session);
    }

    private class IncomingState implements IncomingFrames
    {
        @Override
        public void incomingFrame(Frame frame, Callback callback)
        {
            try
            {
                State state = WebSocketChannel.this.state.get();
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("incomingFrame({}, {}) - connectionState={}, handler={}",
                              frame, callback, state, handler);
                }
                if (state != CLOSED)
                {
                    if (frame.getOpCode() == OpCode.CLOSE)
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

                    handler.onFrame(WebSocketChannel.this, frame, callback);
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

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

import static org.eclipse.jetty.websocket.core.io.WebSocketCoreConnectionState.State;
import static org.eclipse.jetty.websocket.core.io.WebSocketCoreConnectionState.State.CLOSED;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.eclipse.jetty.websocket.core.io.WebSocketRemoteEndpointImpl;

/**
 * The Core WebSocket Session.
 *
 */
public class WebSocketCoreSession extends ContainerLifeCycle
{
    private final Logger LOG = Log.getLogger(this.getClass());

    private final WebSocketPolicy policy;
    private final ContainerLifeCycle parentContainer;
    private final WebSocketLocalEndpoint localEndpoint;
    private final ExtensionStack extensions;
    private final String subprotocol;

    private WebSocketCoreConnection connection;
    private WebSocketRemoteEndpointImpl remoteEndpoint;

    private final AtomicBoolean closeNotified = new AtomicBoolean(false);
    // Holder for errors during open that are reported in doStart later
    private AtomicReference<Throwable> pendingError = new AtomicReference<>();

    public WebSocketCoreSession(ContainerLifeCycle parentContainer,
                                WebSocketLocalEndpoint localEndpoint,
                                WebSocketPolicy policy,
                                ExtensionStack extensions,
                                String subprotocol)
    {
        this.parentContainer = parentContainer;  // TODO not keen on objects adding themselves to containers.
        this.localEndpoint = localEndpoint;
        this.policy = policy;
        this.extensions = extensions;
        this.subprotocol = subprotocol;

        extensions.setNextIncoming(new LocalEndpointHolder());
    }

    public void setWebSocketConnection(WebSocketCoreConnection connection)
    {
        this.connection = connection;
        this.remoteEndpoint = new WebSocketRemoteEndpointImpl(
                new OutgoingFrames()
                {
                    @Override
                    public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode)
                    {
                        if (policy.getBehavior() == WebSocketBehavior.CLIENT && frame instanceof WebSocketFrame)
                        {
                            WebSocketFrame wsFrame = (WebSocketFrame) frame;
                            byte mask[] = new byte[4];
                            ThreadLocalRandom.current().nextBytes(mask); // TODO secure random?
                            wsFrame.setMask(mask);
                        }
                        extensions.outgoingFrame(frame,callback,batchMode);
                    }
                });

        addBean(this.localEndpoint, true);
        addBean(this.remoteEndpoint, true);
    }

    public ExtensionStack getExtensionStack()
    {
        return extensions;
    }


    public void close(int statusCode, String reason, Callback callback)
    {
        close(new CloseStatus(statusCode, reason), callback);
    }

    public void close(CloseStatus closeStatus, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Sending Close Frame");
        CloseFrame closeFrame = new CloseFrame().setPayload(closeStatus);
        extensions.outgoingFrame(closeFrame, callback, BatchMode.OFF);
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public String getSubprotocol()
    {
        return subprotocol;
    }

    /**
     * Process an Error event seen by the Session and/or Connection
     *
     * @param t the raw cause
     */
    public void processError(Throwable t)
    {
        synchronized (pendingError)
        {
            if (!localEndpoint.isOpen())
            {
                // this is a *really* fast fail, before the Session has even started.
                pendingError.compareAndSet(null, t);
                return;
            }
        }

        Throwable cause = getInvokedCause(t);

        notifyError(cause);

        // Forward Errors to User WebSocket Object
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
     * Open/Activate the session
     */
    public void open()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.open()", this.getClass().getSimpleName());

        try
        {
            // Upgrade success
            if (connection.getState().onConnected())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ConnectionState: Transition to CONNECTED");

                // Connect remoteEndpoint
                if (LOG.isDebugEnabled())
                    LOG.debug("{}.open() remoteEndpoint={}", this.getClass().getSimpleName(), remoteEndpoint);

                try
                {
                    // Open WebSocket
                    remoteEndpoint.open();
                    localEndpoint.onOpen(remoteEndpoint);

                    // Open connection
                    if (connection.getState().onOpen())
                    {
                        parentContainer.addManaged(this);

                        if (LOG.isDebugEnabled())
                            LOG.debug("ConnectionState: Transition to OPEN");
                    }
                }
                catch (Throwable t)
                {
                    localEndpoint.getLog().warn("Error during OPEN", t);
                    processError(new CloseException(WebSocketConstants.SERVER_ERROR, t));
                }
                finally
                {
                    notifyOpen();
                }

                /* Perform fillInterested outside of onConnected / onOpen.
                 *
                 * This is to allow for 2 specific scenarios.
                 *
                 * 1) Fast Close
                 *    When an end users WSEndpoint.onOpen() calls
                 *    the Session.close() method.
                 *    This is a state transition of CONNECTING -> CONNECTED -> CLOSING
                 * 2) Fast Fail
                 *    When an end users WSEndpoint.onOpen() throws an Exception.
                 */
                connection.fillInterested();
            }
            else
            {
                throw new IllegalStateException("Unexpected state [" + connection.getState().get() + "] when attempting to transition to CONNECTED");
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t); // TODO log and handle is normally too verbose
            processError(t);
        }
    }

    public WebSocketCoreConnection getConnection()
    {
        return this.connection;
    }

    public ContainerLifeCycle getParentContainer()
    {
        return this.parentContainer;
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
    }

    protected Throwable getInvokedCause(Throwable t)
    {
        // Unwrap any invoker exceptions here.
        return t;
    }

    @Override
    protected void doStart() throws Exception
    {
        Throwable pending = pendingError.get();
        if (pending != null)
        {
            notifyError(pending);
        }
        super.doStart();
    }

    /**
     * Event triggered when the open has completed (successfully or with error).
     */
    protected void notifyOpen()
    {
        // override to trigger behavior
    }

    /**
     * When an error has been produced (and unwrapped in some cases), this
     * method is called to process the error at the local endpoint.
     *
     * @param cause the cause of the error
     */
    protected void notifyError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("notifyError({}) closeNotified={}", cause, closeNotified.get());
        }

        // only notify once
        if (closeNotified.compareAndSet(false, true))
        {
            localEndpoint.onError(cause);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        this.connection.disconnect();
        super.doStop();
    }


    private class LocalEndpointHolder implements IncomingFrames
    {
        @Override
        public void incomingFrame(Frame frame, Callback callback)
        {
            try
            {
                State state = connection.getState().get();
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
}

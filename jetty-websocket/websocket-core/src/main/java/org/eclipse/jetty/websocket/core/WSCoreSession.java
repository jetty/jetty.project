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

import static org.eclipse.jetty.websocket.core.io.WSConnectionState.*;
import static org.eclipse.jetty.websocket.core.io.WSConnectionState.State.*;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.WSConnection;
import org.eclipse.jetty.websocket.core.util.CompletionCallback;

public abstract class WSCoreSession<T extends WSConnection> extends ContainerLifeCycle implements IncomingFrames
{
    // Callbacks
    private Callback onDisconnectCallback = new CompletionCallback()
    {
        @Override
        public void complete()
        {
            if (connection.getState().onClosed())
            {
                if (log.isDebugEnabled())
                    log.debug("ConnectionState: Transition to CLOSED");
                connection.disconnect();
            }
        }
    };

    private final Logger log;
    public final T connection;

    /**
     * The websocket endpoint objects and endpoints
     * Not declared final, as they can be decorated later by other libraries (CDI)
     */
    private Object wsEndpoint;
    private WSLocalEndpoint localEndpoint;
    private WSRemoteEndpoint remoteEndpoint;
    private WSPolicy sessionPolicy;

    private final AtomicBoolean closeNotified = new AtomicBoolean(false);
    // Holder for errors during open that are reported in doStart later
    private AtomicReference<Throwable> pendingError = new AtomicReference<>();

    public WSCoreSession(T connection)
    {
        this.log = Log.getLogger(this.getClass());
        this.connection = connection;
    }

    public void setWebSocketEndpoint(Object endpoint, WSPolicy policy, WSLocalEndpoint localEndpoint, WSRemoteEndpoint remoteEndpoint)
    {
        this.wsEndpoint = endpoint;
        this.sessionPolicy = policy;
        this.localEndpoint = localEndpoint;
        this.remoteEndpoint = remoteEndpoint;
    }

    public void close(int statusCode, String reason, Callback callback)
    {
        connection.close(new CloseStatus(statusCode, reason), callback);
    }

    public void close(CloseStatus closeStatus, Callback callback)
    {
        connection.close(closeStatus, callback);
    }

    public WSPolicy getPolicy()
    {
        if (sessionPolicy == null)
            return connection.getPolicy();
        return sessionPolicy;
    }

    /**
     * Error Event.
     * <p>
     * Can be seen from Session and Connection.
     * </p>
     *
     * @param t the raw cause
     */
    public void onError(Throwable t)
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

        // Forward Errors to User WebSocket Object
        localEndpoint.onError(cause);

        if (cause instanceof Utf8Appendable.NotUtf8Exception)
        {
            close(WSConstants.BAD_PAYLOAD, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof SocketTimeoutException)
        {
            // A path often seen in Windows
            close(WSConstants.SHUTDOWN, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof IOException)
        {
            close(WSConstants.PROTOCOL, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof SocketException)
        {
            // A path unique to Unix
            close(WSConstants.SHUTDOWN, cause.getMessage(), onDisconnectCallback);
        }
        else if (cause instanceof CloseException)
        {
            CloseException ce = (CloseException) cause;
            Callback callback = Callback.NOOP;

            // Force disconnect for protocol breaking status codes
            switch (ce.getStatusCode())
            {
                case WSConstants.PROTOCOL:
                case WSConstants.BAD_DATA:
                case WSConstants.BAD_PAYLOAD:
                case WSConstants.MESSAGE_TOO_LARGE:
                case WSConstants.POLICY_VIOLATION:
                case WSConstants.SERVER_ERROR:
                {
                    callback = onDisconnectCallback;
                }
            }

            close(ce.getStatusCode(), ce.getMessage(), callback);
        }
        else if (cause instanceof WSTimeoutException)
        {
            close(WSConstants.SHUTDOWN, cause.getMessage(), onDisconnectCallback);
        }
        else
        {
            log.warn("Unhandled Error (closing connection)", cause);

            // Exception on end-user WS-Endpoint.
            // Fast-fail & close connection with reason.
            int statusCode = WSConstants.SERVER_ERROR;
            if (getPolicy().getBehavior() == WSBehavior.CLIENT)
            {
                statusCode = WSConstants.POLICY_VIOLATION;
            }
            close(statusCode, cause.getMessage(), Callback.NOOP);
        }
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (log.isDebugEnabled())
            log.debug("{}.open()", this.getClass().getSimpleName());

        if (remoteEndpoint != null)
        {
            // already opened
            return;
        }

        try
        {
            // Upgrade success
            if (connection.getState().onConnected())
            {
                if (log.isDebugEnabled())
                    log.debug("ConnectionState: Transition to CONNECTED");

                // Connect remoteEndpoint
                if (log.isDebugEnabled())
                    log.debug("{}.open() remoteEndpoint={}", this.getClass().getSimpleName(), remoteEndpoint);

                try
                {
                    // Open WebSocket
                    localEndpoint.onOpen();

                    // Open connection
                    if (connection.getState().onOpen())
                    {
                        if (log.isDebugEnabled())
                            log.debug("ConnectionState: Transition to OPEN");
                    }
                }
                catch (Throwable t)
                {
                    localEndpoint.getLog().warn("Error during OPEN", t);
                    onError(new CloseException(WSConstants.SERVER_ERROR, t));
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
            log.warn(t);
            onError(t);
        }
    }

    public T getConnection()
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

    @Override
    public void incomingFrame(Frame frame, Callback callback)
    {
        try
        {
            State state = connection.getState().get();
            if (log.isDebugEnabled())
            {
                log.debug("incomingFrame({}, {}) - connectionState={}, localEndpoint={}",
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

                        if (connection.getState().onClosing())
                        {
                            if (log.isDebugEnabled())
                                log.debug("ConnectionState: Transition to CLOSING");
                            CloseFrame closeframe = (CloseFrame) frame;
                            CloseStatus closeStatus = closeframe.getCloseStatus();
                            notifyClose(closeStatus);
                            close(closeStatus, onDisconnectCallback);
                        }
                        else if (connection.getState().onClosed())
                        {
                            if (log.isDebugEnabled())
                                log.debug("ConnectionState: Transition to CLOSED");
                            CloseFrame closeframe = (CloseFrame) frame;
                            CloseStatus closeStatus = closeframe.getCloseStatus();
                            notifyClose(closeStatus);
                            connection.disconnect();
                        }
                        else
                        {
                            if (log.isDebugEnabled())
                                log.debug("ConnectionState: {} - Close Frame Received", state);
                        }

                        callback.succeeded();
                        return;
                    }
                    case OpCode.PING:
                    {
                        if (log.isDebugEnabled())
                            log.debug("PING: {}", BufferUtil.toDetailString(frame.getPayload()));

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
                            if (log.isDebugEnabled())
                                log.debug("Unable to send pong", t);
                        }
                        break;
                    }
                    case OpCode.PONG:
                    {
                        if (log.isDebugEnabled())
                            log.debug("PONG: {}", BufferUtil.toDetailString(frame.getPayload()));

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
                        if (log.isDebugEnabled())
                            log.debug("Unhandled OpCode: {}", opcode);
                    }
                }
            }
            else
            {
                if (log.isDebugEnabled())
                    log.debug("Discarding post EOF frame - {}", frame);
            }
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    public void notifyClose(CloseStatus closeStatus)
    {
        if (log.isDebugEnabled())
        {
            log.debug("notifyClose({}) closeNotified={}", closeStatus, closeNotified.get());
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

    // TODO: consider onError?
    private void notifyError(Throwable cause)
    {
        if (log.isDebugEnabled())
        {
            log.debug("notifyError({}) closeNotified={}", cause, closeNotified.get());
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
}

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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.channels.ClosedChannelException;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;

/**
 * Atomic Connection State
 */
public class WebSocketSessionState
{
    enum WebSocketState
    {
        CONNECTING,
        CONNECTED,
        OPEN,
        ISHUT,
        OSHUT,
        CLOSED
    }

    enum EndPointState
    {
        OPEN,
        ISHUT,
        OSHUT,
        CLOSED
    }

    public record Result(boolean notifyWebSocketClose, boolean closeEndpoint) {}
    public record EofResult(boolean notifyWebSocketClose, boolean closeEndpoint, boolean shutdownOutput){}
    public record CloseResult(boolean shutdownOutput, boolean closeEndpoint){}

    private final AutoLock _lock = new AutoLock();
    private final Behavior _behavior;
    private WebSocketState _webSocketState = WebSocketState.CONNECTING;
    private EndPointState _endPointState = EndPointState.OPEN;
    private byte _incomingContinuation = OpCode.UNDEFINED;
    private byte _outgoingContinuation = OpCode.UNDEFINED;
    CloseStatus _closeStatus = null;

    public WebSocketSessionState(Behavior behavior)
    {
        _behavior = behavior;
    }

    public void onConnected()
    {
        try (AutoLock l = _lock.lock())
        {
            if (_webSocketState != WebSocketState.CONNECTING)
                throw new IllegalStateException(_webSocketState.toString());

            _webSocketState = WebSocketState.CONNECTED;
        }
    }

    public void onOpen()
    {
        try (AutoLock l = _lock.lock())
        {
            switch (_webSocketState)
            {
                case CONNECTED:
                    _webSocketState = WebSocketState.OPEN;
                    break;

                case OSHUT:
                case CLOSED:
                    // Already closed in onOpen handler
                    break;

                default:
                    throw new IllegalStateException(_webSocketState.toString());
            }
        }
    }

    private WebSocketState getState()
    {
        try (AutoLock l = _lock.lock())
        {
            return _webSocketState;
        }
    }

    public boolean isClosed()
    {
        return getState() == WebSocketState.CLOSED;
    }

    public boolean isInputOpen()
    {
        WebSocketState state = getState();
        return (state == WebSocketState.OPEN || state == WebSocketState.OSHUT);
    }

    public boolean isOutputOpen()
    {
        WebSocketState state = getState();
        return (state == WebSocketState.CONNECTED || state == WebSocketState.OPEN || state == WebSocketState.ISHUT);
    }

    public CloseStatus getCloseStatus()
    {
        try (AutoLock l = _lock.lock())
        {
            return _closeStatus;
        }
    }

    /**
     * <p>
     * If no error is set in the CloseStatus this will either, replace the current close status with
     * a {@link CloseStatus#SERVER_ERROR} status if we had a NORMAL close code, or, it will set the cause
     * of the CloseStatus if the previous cause was null, this allows onError to be notified after the connection is closed.
     * </p>
     * <p>
     * This should only be called if there is an error directly before the call to
     * {@code  WebSocketCoreSession#notifyWebSocketConnectionClose(CloseStatus, Callback)}.
     * </p>
     * <p>
     * This could occur if the FrameHandler throws an exception in onFrame after receiving a close frame reply, in this
     * case to notify onError we must set the cause in the closeStatus.
     * </p>
     * @param t the error which occurred.
     * @return true if the endpoint should be closed.
     */
    public boolean onError(Throwable t)
    {
        try (AutoLock l = _lock.lock())
        {
            if (_webSocketState != WebSocketState.CLOSED || _closeStatus == null)
                throw new IllegalArgumentException();

            // Override any normal close status.
            if (!_closeStatus.isAbnormal())
                _closeStatus = new CloseStatus(CloseStatus.SERVER_ERROR, t);

            // Otherwise set the error if it wasn't already set to notify onError as well as onClose.
            if (_closeStatus.getCause() == null)
                _closeStatus = new CloseStatus(_closeStatus.getCode(), _closeStatus.getReason(), t);

            return lockedForceCloseEndpointState();
        }
    }

    public Result onClosed(CloseStatus closeStatus)
    {
        try (AutoLock l = _lock.lock())
        {
            boolean closeEndpoint = lockedForceCloseEndpointState();
            boolean notifyWebSocketClose = false;
            if (_webSocketState != WebSocketState.CLOSED)
            {
                _closeStatus = closeStatus;
                _webSocketState = WebSocketState.CLOSED;
                notifyWebSocketClose = true;
            }

            return new Result(notifyWebSocketClose, closeEndpoint);
        }
    }

    /**
     * Handle an EOF from the transport.
     * @return a pair of booleans;
     *  The first indicates whether the websocket listeners should be notified of close.
     *  The second indicates whether the underlying endpoint should be closed.
     */
    public EofResult onEof()
    {
        try (AutoLock l = _lock.lock())
        {
            return switch (_webSocketState)
            {
                case CLOSED ->
                {
                    boolean closeEndpoint = lockedForceCloseEndpointState();
                    yield new EofResult(false, closeEndpoint, false);
                }
                case ISHUT ->
                {
                    boolean closeEndpoint = false;
                    boolean shutdownOutput = false;
                    switch (_endPointState)
                    {
                        case OPEN -> _endPointState = EndPointState.ISHUT;
                        case CLOSED, ISHUT ->
                        { /* NOOP */ }
                        case OSHUT ->
                        {
                            // If this was a client it didn't shut down output when it sent the close frame because of RFC6455 7.1.1.
                            // So we should do the shutdown output before closing the endpoint.
                            shutdownOutput = _behavior == Behavior.CLIENT;
                            closeEndpoint = true;
                            _endPointState = EndPointState.CLOSED;
                        }
                        default -> throw new IllegalStateException(_endPointState.toString());
                    }
                    yield new EofResult(false, closeEndpoint, shutdownOutput);
                }
                default ->
                {
                    if (_closeStatus == null || CloseStatus.isOrdinary(_closeStatus.getCode()))
                        _closeStatus = new CloseStatus(CloseStatus.NO_CLOSE, "Session Closed", new ClosedChannelException());
                    _webSocketState = WebSocketState.CLOSED;

                    boolean closeEndpoint = lockedForceCloseEndpointState();
                    yield new EofResult(true, closeEndpoint, false);
                }
            };
        }
    }

    public CloseResult onCloseFrameSent()
    {
        try (AutoLock l = _lock.lock())
        {
            return switch (_endPointState)
            {
                case OPEN ->
                {
                    _endPointState = EndPointState.OSHUT;
                    // We only shut down output if we are a server because of RFC6455 7.1.1.
                    // When the client receives an EOF it will shut down its output.
                    yield new CloseResult(_behavior == Behavior.SERVER, false);
                }
                case ISHUT ->
                {
                    // We have already read EOF so we can shut down output even if we're a client.
                    _endPointState = EndPointState.CLOSED;
                    yield new CloseResult(true, true);
                }
                case OSHUT, CLOSED -> new CloseResult(false, false);
            };
        }
    }

    public Result onOutgoingFrame(Frame frame) throws Exception
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        try (AutoLock l = _lock.lock())
        {
            if (!isOutputOpen())
                throw new ClosedChannelException();

            if (opcode == OpCode.CLOSE)
            {
                _closeStatus = CloseStatus.getCloseStatus(frame);
                if (_closeStatus.isAbnormal())
                {
                    boolean closeEndpoint = lockedForceCloseEndpointState();
                    _webSocketState = WebSocketState.CLOSED;
                    return new Result(true, closeEndpoint);
                }

                return switch (_webSocketState)
                {
                    case CONNECTED, OPEN ->
                    {
                        _webSocketState = WebSocketState.OSHUT;
                        yield new Result(false, false);
                    }
                    case ISHUT ->
                    {
                        _webSocketState = WebSocketState.CLOSED;
                        yield new Result(true, false);
                    }
                    default -> throw new IllegalStateException(_webSocketState.toString());
                };
            }
            else if (frame.isDataFrame())
            {
                _outgoingContinuation = checkDataSequence(opcode, fin, _outgoingContinuation);
            }
        }

        return new Result(false, false);
    }

    public Result onIncomingFrame(Frame frame) throws ProtocolException, ClosedChannelException
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        try (AutoLock l = _lock.lock())
        {
            if (!isInputOpen())
                throw new ClosedChannelException();

            if (opcode == OpCode.CLOSE)
            {
                _closeStatus = CloseStatus.getCloseStatus(frame);

                switch (_webSocketState)
                {
                    case OPEN:
                        _webSocketState = WebSocketState.ISHUT;
                        return new Result(false, false);
                    case OSHUT:
                        // If we received abnormal status close, and we cannot send a response because we are OSHUT,
                        // so we should close underlying the connection.
                        boolean closeEndpoint = _closeStatus.isAbnormal() && lockedForceCloseEndpointState();
                        _webSocketState = WebSocketState.CLOSED;
                        return new Result(true, closeEndpoint);
                    default:
                        throw new IllegalStateException(_webSocketState.toString());
                }
            }
            else if (frame.isDataFrame())
            {
                _incomingContinuation = checkDataSequence(opcode, fin, _incomingContinuation);
            }
        }

        return new Result(false, false);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,i=%s,o=%s,c=%s}", TypeUtil.toShortName(getClass()), hashCode(),
            _webSocketState,
            OpCode.name(_incomingContinuation),
            OpCode.name(_outgoingContinuation),
            _closeStatus);
    }

    private boolean lockedForceCloseEndpointState()
    {
        assert _lock.isHeldByCurrentThread();

        boolean closeEndpoint = false;
        if (_endPointState != EndPointState.CLOSED)
        {
            _endPointState = EndPointState.CLOSED;
            closeEndpoint = true;
        }
        return closeEndpoint;
    }

    private static byte checkDataSequence(byte opcode, boolean fin, byte lastOpCode) throws ProtocolException
    {
        switch (opcode)
        {
            case OpCode.TEXT:
            case OpCode.BINARY:
                if (lastOpCode != OpCode.UNDEFINED)
                    throw new ProtocolException("DataFrame before fin==true");
                if (!fin)
                    return opcode;
                return OpCode.UNDEFINED;

            case OpCode.CONTINUATION:
                if (lastOpCode == OpCode.UNDEFINED)
                    throw new ProtocolException("CONTINUATION after fin==true");
                if (fin)
                    return OpCode.UNDEFINED;
                return lastOpCode;

            default:
                return lastOpCode;
        }
    }
}

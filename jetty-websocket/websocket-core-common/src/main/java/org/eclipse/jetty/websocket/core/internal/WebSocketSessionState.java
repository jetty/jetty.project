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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.channels.ClosedChannelException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;

/**
 * Atomic Connection State
 */
public class WebSocketSessionState
{
    enum State
    {
        CONNECTING,
        CONNECTED,
        OPEN,
        ISHUT,
        OSHUT,
        CLOSED
    }

    private final AutoLock lock = new AutoLock();
    private State _sessionState = State.CONNECTING;
    private byte _incomingContinuation = OpCode.UNDEFINED;
    private byte _outgoingContinuation = OpCode.UNDEFINED;
    CloseStatus _closeStatus = null;

    public void onConnected()
    {
        try (AutoLock l = lock.lock())
        {
            if (_sessionState != State.CONNECTING)
                throw new IllegalStateException(_sessionState.toString());

            _sessionState = State.CONNECTED;
        }
    }

    public void onOpen()
    {
        try (AutoLock l = lock.lock())
        {
            switch (_sessionState)
            {
                case CONNECTED:
                    _sessionState = State.OPEN;
                    break;

                case OSHUT:
                case CLOSED:
                    // Already closed in onOpen handler
                    break;

                default:
                    throw new IllegalStateException(_sessionState.toString());
            }
        }
    }

    private State getState()
    {
        try (AutoLock l = lock.lock())
        {
            return _sessionState;
        }
    }

    public boolean isClosed()
    {
        return getState() == State.CLOSED;
    }

    public boolean isInputOpen()
    {
        State state = getState();
        return (state == State.OPEN || state == State.OSHUT);
    }

    public boolean isOutputOpen()
    {
        State state = getState();
        return (state == State.CONNECTED || state == State.OPEN || state == State.ISHUT);
    }

    public CloseStatus getCloseStatus()
    {
        try (AutoLock l = lock.lock())
        {
            return _closeStatus;
        }
    }

    public boolean onClosed(CloseStatus closeStatus)
    {
        try (AutoLock l = lock.lock())
        {
            if (_sessionState == State.CLOSED)
                return false;

            _closeStatus = closeStatus;
            _sessionState = State.CLOSED;
            return true;
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
     * {@link WebSocketCoreSession#closeConnection(CloseStatus, Callback)}.
     * </p>
     * <p>
     * This could occur if the FrameHandler throws an exception in onFrame after receiving a close frame reply, in this
     * case to notify onError we must set the cause in the closeStatus.
     * </p>
     * @param t the error which occurred.
     */
    public void onError(Throwable t)
    {
        try (AutoLock l = lock.lock())
        {
            if (_sessionState != State.CLOSED || _closeStatus == null)
                throw new IllegalArgumentException();

            // Override any normal close status.
            if (!_closeStatus.isAbnormal())
                _closeStatus = new CloseStatus(CloseStatus.SERVER_ERROR, t);

            // Otherwise set the error if it wasn't already set to notify onError as well as onClose.
            if (_closeStatus.getCause() == null)
                _closeStatus = new CloseStatus(_closeStatus.getCode(), _closeStatus.getReason(), t);
        }
    }

    public boolean onEof()
    {
        try (AutoLock l = lock.lock())
        {
            switch (_sessionState)
            {
                case CLOSED:
                case ISHUT:
                    return false;

                default:
                    if (_closeStatus == null || CloseStatus.isOrdinary(_closeStatus.getCode()))
                        _closeStatus = new CloseStatus(CloseStatus.NO_CLOSE, "Session Closed", new ClosedChannelException());
                    _sessionState = State.CLOSED;
                    return true;
            }
        }
    }

    public boolean onOutgoingFrame(Frame frame) throws Exception
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        try (AutoLock l = lock.lock())
        {
            if (!isOutputOpen())
                throw new ClosedChannelException();

            if (opcode == OpCode.CLOSE)
            {
                _closeStatus = CloseStatus.getCloseStatus(frame);
                if (_closeStatus.isAbnormal())
                {
                    _sessionState = State.CLOSED;
                    return true;
                }

                switch (_sessionState)
                {
                    case CONNECTED:
                    case OPEN:
                        _sessionState = State.OSHUT;
                        return false;

                    case ISHUT:
                        _sessionState = State.CLOSED;
                        return true;

                    default:
                        throw new IllegalStateException(_sessionState.toString());
                }
            }
            else if (frame.isDataFrame())
            {
                _outgoingContinuation = checkDataSequence(opcode, fin, _outgoingContinuation);
            }
        }

        return false;
    }

    public boolean onIncomingFrame(Frame frame) throws ProtocolException, ClosedChannelException
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        try (AutoLock l = lock.lock())
        {
            if (!isInputOpen())
                throw new ClosedChannelException();

            if (opcode == OpCode.CLOSE)
            {
                _closeStatus = CloseStatus.getCloseStatus(frame);

                switch (_sessionState)
                {
                    case OPEN:
                        _sessionState = State.ISHUT;
                        return false;
                    case OSHUT:
                        _sessionState = State.CLOSED;
                        return true;
                    default:
                        throw new IllegalStateException(_sessionState.toString());
                }
            }
            else if (frame.isDataFrame())
            {
                _incomingContinuation = checkDataSequence(opcode, fin, _incomingContinuation);
            }
        }

        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,i=%s,o=%s,c=%s}", getClass().getSimpleName(), hashCode(),
            _sessionState,
            OpCode.name(_incomingContinuation),
            OpCode.name(_outgoingContinuation),
            _closeStatus);
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

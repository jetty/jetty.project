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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.channels.ClosedChannelException;

import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;

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

    private State _sessionState = State.CONNECTING;
    private byte _incomingContinuation = OpCode.UNDEFINED;
    private byte _outgoingContinuation = OpCode.UNDEFINED;
    CloseStatus _closeStatus = null;

    public void onConnected()
    {
        synchronized (this)
        {
            if (_sessionState != State.CONNECTING)
                throw new IllegalStateException(_sessionState.toString());

            _sessionState = State.CONNECTED;
        }
    }

    public void onOpen()
    {
        synchronized (this)
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

    public State getState()
    {
        synchronized (this)
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
        synchronized (this)
        {
            return _closeStatus;
        }
    }

    public boolean onClosed(CloseStatus closeStatus)
    {
        synchronized (this)
        {
            if (_sessionState == State.CLOSED)
                return false;

            _closeStatus = closeStatus;
            _sessionState = State.CLOSED;
            return true;
        }
    }

    public boolean onEof()
    {
        synchronized (this)
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

    public boolean onOutgoingFrame(Frame frame) throws ProtocolException
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        synchronized (this)
        {
            if (!isOutputOpen())
                throw new IllegalStateException(_sessionState.toString());

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

    public boolean onIncomingFrame(Frame frame) throws ProtocolException
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        synchronized (this)
        {
            if (!isInputOpen())
                throw new IllegalStateException(_sessionState.toString());

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

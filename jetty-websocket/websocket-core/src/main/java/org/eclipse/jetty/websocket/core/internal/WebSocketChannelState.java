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

import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.ProtocolException;

/**
 * Atomic Connection State
 */
public class WebSocketChannelState
{
    enum State
    {
        CONNECTING,
        CONNECTED,
        OPEN,
        ICLOSED,
        OCLOSED,
        CLOSED;

        public boolean isClosed()
        {
            return this.equals(CLOSED);
        }

        public boolean isInOpen()
        {
            if (this.equals(OPEN) || this.equals(OCLOSED))
                return true;

            return false;
        }

        public boolean isOutOpen()
        {
            if (this.equals(OPEN) || this.equals(ICLOSED))
                return true;

            return false;
        }
    }

    private State _channelState = State.CONNECTING;
    private byte _incomingSequence = OpCode.UNDEFINED;
    private byte _outgoingSequence = OpCode.UNDEFINED;
    CloseStatus _closeStatus = null;


    public void onConnected()
    {
        synchronized (this)
        {
            if (!_channelState.equals(State.CONNECTING))
                throw new IllegalStateException(_channelState.toString());

            _channelState = State.CONNECTED;
        }
    }

    public void onOpen()
    {
        synchronized (this)
        {
            if (!_channelState.equals(State.CONNECTED))
                throw new IllegalStateException(_channelState.toString());

            _channelState = State.OPEN;
        }
    }

    @Override
    public String toString()
    {
        return _channelState.toString();
    }


    public State getState()
    {
        synchronized (this)
        {
            return _channelState;
        }
    }

    public boolean isClosed()
    {
        return getState().isClosed();
    }

    public boolean isInOpen()
    {
        return getState().isInOpen();
    }

    public boolean isOutOpen()
    {
        return getState().isOutOpen();
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
            if (_channelState.equals(State.CLOSED))
                return false;

            _closeStatus = closeStatus;
            _channelState = State.CLOSED;
            return true;
        }
    }


    public boolean checkOutgoing(Frame frame) throws ProtocolException
    {
        synchronized (this)
        {
            _outgoingSequence = getNextState(frame, _outgoingSequence);

            if (frame.getOpCode() == OpCode.CLOSE)
            {
                _closeStatus = CloseStatus.getCloseStatus(frame);
                _channelState = updateChannelState(_channelState, _incomingSequence, _outgoingSequence);
            }

            return _channelState.equals(State.CLOSED);
        }
    }



    public boolean checkIncoming(Frame frame) throws ProtocolException
    {
        synchronized (this)
        {
            _incomingSequence = getNextState(frame, _incomingSequence);

            if (frame.getOpCode() == OpCode.CLOSE)
            {
                _closeStatus = CloseStatus.getCloseStatus(frame);
                _channelState = updateChannelState(_channelState, _incomingSequence, _outgoingSequence);
            }

            return _channelState.equals(State.CLOSED);
        }
    }



    private static State updateChannelState(State state, byte _incomingSequence, byte _outgoingSequence)
    {
        switch (state)
        {
            case OPEN:
            case ICLOSED:
            case OCLOSED:
                break;
            default:
                throw new IllegalStateException(state.toString());
        }

        State newState = state;
        if ((_outgoingSequence == OpCode.CLOSE) && (_incomingSequence == OpCode.CLOSE))
            newState = State.CLOSED;
        else if (_outgoingSequence == OpCode.CLOSE)
            newState = State.OCLOSED;
        else if (_incomingSequence == OpCode.CLOSE)
            newState = State.ICLOSED;

        return newState;
    }


    public static byte getNextState(Frame frame, byte state) throws ProtocolException
    {
        byte opcode = frame.getOpCode();
        boolean fin = frame.isFin();

        if (state == OpCode.CLOSE)
            throw new ProtocolException(OpCode.name(opcode) + " after CLOSE");

        switch (opcode)
        {
            case OpCode.UNDEFINED:
                throw new ProtocolException("UNDEFINED OpCode: " + OpCode.name(opcode));

            case OpCode.CONTINUATION:
                if (state == OpCode.UNDEFINED)
                    throw new ProtocolException("CONTINUATION after fin==true");
                if (fin)
                    return OpCode.UNDEFINED;
                return state;

            case OpCode.CLOSE:
                return OpCode.CLOSE;

            case OpCode.PING:
            case OpCode.PONG:
                return state;

            case OpCode.TEXT:
            case OpCode.BINARY:
            default:
                if (state != OpCode.UNDEFINED)
                    throw new ProtocolException("DataFrame before fin==true");
                if (!fin)
                    return opcode;

                return OpCode.UNDEFINED;
        }
    }
}

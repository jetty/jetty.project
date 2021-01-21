//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.io;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket Connection State.
 * <p>
 * {@code State} can only go in one direction from {@code HANDSHAKING} to {@code DISCONNECTED}.
 * </p>
 */
public class ConnectionState
{
    private final AtomicReference<State> state = new AtomicReference<>(State.HANDSHAKING);
    private final AtomicBoolean wasOpened = new AtomicBoolean(false);

    /**
     * Test to see if state allows writing of WebSocket frames
     *
     * @return true if state allows for writing of websocket frames
     */
    public boolean canWriteWebSocketFrames()
    {
        State current = state.get();
        return current == State.OPENING || current == State.OPENED;
    }

    /**
     * Tests to see if state allows for reading of WebSocket frames
     *
     * @return true if state allows for reading of websocket frames
     */
    public boolean canReadWebSocketFrames()
    {
        State current = state.get();
        return current == State.OPENED || current == State.CLOSING;
    }

    /**
     * Tests to see if state got past the initial HANDSHAKING state
     *
     * @return true if the connection state was opened
     */
    public boolean wasOpened()
    {
        return wasOpened.get();
    }

    /**
     * Requests that the connection migrate to OPENING state
     *
     * @return true if OPENING state attained
     */
    public boolean opening()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case HANDSHAKING:
                    if (state.compareAndSet(current, State.OPENING))
                    {
                        wasOpened.set(true);
                        return true;
                    }
                    break;
                case CLOSING: // Connection closed from WebSocketSession doStop before connection was opened)
                    return false;
                case DISCONNECTED:
                    return false;
                default:
                    throw new IllegalStateException(toString(current));
            }
        }
    }

    /**
     * Requests that the connection migrate to OPENED state
     *
     * @return true if OPENED state attained
     */
    public boolean opened()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case OPENING:
                    if (state.compareAndSet(current, State.OPENED))
                    {
                        return true;
                    }
                    break;
                case CLOSING: // connection went from OPENING to CLOSING (likely to to failure to onOpen)
                    return false;
                case DISCONNECTED:
                    return false;
                default:
                    throw new IllegalStateException(toString(current));
            }
        }
    }

    /**
     * The Local Endpoint wants to close.
     *
     * @return true if this is the first local close
     */
    public boolean closing()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case HANDSHAKING:
                case OPENING:
                case OPENED:
                    if (state.compareAndSet(current, State.CLOSING))
                    {
                        return true;
                    }
                    break;
                case CLOSING: // already closing
                    return false;
                case DISCONNECTED:
                    return false;
                default:
                    throw new IllegalStateException(toString(current));
            }
        }
    }

    /**
     * Final Terminal state indicating the connection is disconnected
     *
     * @return true if disconnected reached for the first time
     */
    public boolean disconnected()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case DISCONNECTED:
                    return false;
                default:
                    if (state.compareAndSet(current, State.DISCONNECTED))
                    {
                        return true;
                    }
                    break;
            }
        }
    }

    private String toString(State state)
    {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), state);
    }

    @Override
    public String toString()
    {
        return toString(state.get());
    }

    private enum State
    {
        /**
         * Initial state of a connection, the upgrade request / response handshake is in progress
         */
        HANDSHAKING,

        /**
         * Intermediate state between HANDSHAKING and OPENED, used to indicate that a upgrade
         * request/response handshake is successful, but the Application provided socket's
         * onOpen code has yet completed.
         * <p>
         * This state is to allow the local socket endpoint to initiate the sending of messages and
         * frames, but to NOT start reading yet.
         */
        OPENING,

        /**
         * The websocket connection is established and open.
         * <p>
         * This indicates that the Upgrade has succeed, and the Application provided
         * socket's onOpen code has returned.
         * <p>
         * It is now time to start reading from the remote endpoint.
         */
        OPENED,

        /**
         * The WebSocket is closing
         * <p>
         * There are several conditions that would start this state.
         * <p>
         * - A CLOSE Frame has been received (and parsed) from the remote endpoint
         * - A CLOSE Frame has been enqueued by the local endpoint
         */
        CLOSING,

        /**
         * The WebSocket connection is disconnected.
         * <p>
         * Connection is complete and no longer valid.
         */
        DISCONNECTED
    }
}

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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic Connection State
 */
public class WebSocketSessionState
{
    /**
     * Connection states as outlined in <a href="https://tools.ietf.org/html/rfc6455">RFC6455</a>.
     */
    public enum State
    {
        /** [RFC] Initial state of a connection, the upgrade request / response is in progress */
        CONNECTING,

        /**
         * [Impl] Intermediate state between CONNECTING and OPEN, used to indicate that a upgrade request/response is successful, but the end-user provided socket's
         * onOpen code has yet to run.
         * <p>
         * This state is to allow the local socket to initiate messages and frames, but to NOT start reading yet.
         * </p>
         */
        CONNECTED,

        /**
         * [RFC] The websocket connection is established and onOpen.
         * <p>
         * This indicates that the Upgrade has succeed, and the end-user provided socket's onOpen code has completed.
         * <p>
         * It is now time to start reading from the remote endpoint.
         * </p>
         */
        OPEN,

        /**
         * [RFC] The websocket closing handshake is started.
         * <p>
         * This can be considered a half-closed state.
         * </p>
         */
        CLOSING,

        /**
         * [RFC] The websocket connection is closed.
         * <p>
         * Connection should be disconnected and no further reads or writes should occur.
         * </p>
         */
        CLOSED
    }
    
    private AtomicReference<State> state = new AtomicReference<>(State.CONNECTING);
    
    public State get()
    {
        return state.get();
    }
    
    public boolean onClosed()
    {
        return state.compareAndSet(State.CLOSING, State.CLOSED);
    }
    
    public boolean onClosing()
    {
        while(true)
        {
            State s = state.get();
            switch(s)
            {
                case CONNECTING:
                case CONNECTED:
                case OPEN:
                    if (state.compareAndSet(s,State.CLOSING))
                        return true;
                    break;
                case CLOSING:
                case CLOSED:
                    return false;
            }
        }
    }
    
    public void onConnected()
    {
        if (!state.compareAndSet(State.CONNECTING, State.CONNECTED))
            throw new IllegalStateException(state.get().toString());
    }

    public void onOpen()
    {
        if (!state.compareAndSet(State.CONNECTED, State.OPEN))
            throw new IllegalStateException(state.get().toString());
    }
    
    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), state.get());
    }


    public boolean isOpen()
    {
        State s = state.get();
        switch(s)
        {
            case CONNECTING:
                return false;
            case CONNECTED:
            case OPEN:
                return true;
            default:
                return false;
        }
    }

}

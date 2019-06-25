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

package org.eclipse.jetty.websocket.common.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

class ReadState
{
    public static final ByteBuffer NO_ACTION = BufferUtil.EMPTY_BUFFER;

    private State state = State.READING;
    private ByteBuffer buffer;

    boolean isReading()
    {
        synchronized (this)
        {
            return state == State.READING;
        }
    }

    boolean isSuspended()
    {
        synchronized (this)
        {
            return state == State.SUSPENDED || state == State.EOF;
        }
    }

    /**
     * Requests that reads from the connection be suspended when {@link #suspend()} is called.
     *
     * @return whether the suspending was successful
     */
    boolean suspending()
    {
        synchronized (this)
        {
            switch (state)
            {
                case READING:
                    state = State.SUSPENDING;
                    return true;
                case EOF:
                    return false;
                default:
                    throw new IllegalStateException(toString(state));
            }
        }
    }

    public boolean suspendParse(ByteBuffer buffer)
    {
        synchronized (this)
        {
            switch (state)
            {
                case READING:
                    return false;
                case SUSPENDING:
                    this.buffer = buffer;
                    this.state = State.SUSPENDED;
                    return true;
                default:
                    throw new IllegalStateException(toString(state));
            }
        }
    }

    /**
     * Suspends reads from the connection if {@link #suspending()} was called.
     *
     * @return whether reads from the connection should be suspended
     */
    boolean suspend()
    {
        synchronized (this)
        {
            switch (state)
            {
                case READING:
                    return false;
                case SUSPENDING:
                    state = State.SUSPENDED;
                    return true;
                case SUSPENDED:
                    if (buffer == null)
                        throw new IllegalStateException();
                    return true;
                case EOF:
                    return true;
                default:
                    throw new IllegalStateException(toString(state));
            }
        }
    }

    /**
     * @return a ByteBuffer to finish processing, or null if we should register fillInterested
     * If return value is {@link BufferUtil#EMPTY_BUFFER} no action should be taken.
     */
    ByteBuffer resume()
    {
        synchronized (this)
        {
            switch (state)
            {
                case SUSPENDING:
                    state = State.READING;
                    return NO_ACTION;
                case SUSPENDED:
                    state = State.READING;
                    ByteBuffer bb = buffer;
                    buffer = null;
                    return bb;
                case EOF:
                    return NO_ACTION;
                default:
                    throw new IllegalStateException(toString(state));
            }
        }
    }

    void eof()
    {
        synchronized (this)
        {
            state = State.EOF;
        }
    }

    private String toString(State state)
    {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), state);
    }

    @Override
    public String toString()
    {
        synchronized (this)
        {
            return toString(state);
        }
    }

    private enum State
    {
        /**
         * Reading from the connection.
         */
        READING,

        /**
         * Suspend has been requested but not yet taken effect.
         */
        SUSPENDING,

        /**
         * Suspended, won't read from the connection until resumed.
         */
        SUSPENDED,

        /**
         * Won't read from the connection (terminal state).
         */
        EOF
    }
}

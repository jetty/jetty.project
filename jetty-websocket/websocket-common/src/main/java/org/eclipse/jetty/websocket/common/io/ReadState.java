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

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

class ReadState
{
    private static final Logger LOG = Log.getLogger(ReadState.class);

    private State state = State.READING;
    private ByteBuffer buffer;

    ByteBuffer getBuffer()
    {
        synchronized (this)
        {
            return buffer;
        }
    }

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

    Action getAction(ByteBuffer buffer)
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} getAction({})", this, BufferUtil.toDetailString(buffer));

            switch (state)
            {
                case READING:
                    return buffer.hasRemaining() ? Action.PARSE : Action.FILL;

                case SUSPENDING:
                    this.buffer = buffer;
                    this.state = State.SUSPENDED;
                    return Action.SUSPEND;

                case EOF:
                    return Action.EOF;

                case DISCARDING:
                    return buffer.hasRemaining() ? Action.DISCARD : Action.FILL;

                case SUSPENDED:
                default:
                    throw new IllegalStateException(toString(state));
            }
        }
    }

    /**
     * Requests that reads from the connection be suspended.
     */
    void suspending()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("suspending {}", state);

            switch (state)
            {
                case READING:
                    state = State.SUSPENDING;
                    break;
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
            if (LOG.isDebugEnabled())
                LOG.debug("resuming {}", state);

            switch (state)
            {
                case SUSPENDING:
                    state = State.READING;
                    return null;
                case SUSPENDED:
                    state = State.READING;
                    ByteBuffer bb = buffer;
                    buffer = null;
                    return bb;
                default:
                    throw new IllegalStateException(toString(state));
            }
        }
    }

    void eof()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("eof {}", state);

            state = State.EOF;
        }
    }

    void discard()
    {
        synchronized (this)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("discard {}", state);

            switch (state)
            {
                case READING:
                case SUSPENDED:
                case SUSPENDING:
                    state = State.DISCARDING;
                    break;

                case DISCARDING:
                case EOF:
                default:
                    throw new IllegalStateException(toString(state));
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
        synchronized (this)
        {
            return toString(state);
        }
    }

    enum Action
    {
        FILL,
        PARSE,
        DISCARD,
        SUSPEND,
        EOF
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
         * Reading from connection and discarding bytes until EOF.
         */
        DISCARDING,

        /**
         * Won't read from the connection (terminal state).
         */
        EOF
    }
}

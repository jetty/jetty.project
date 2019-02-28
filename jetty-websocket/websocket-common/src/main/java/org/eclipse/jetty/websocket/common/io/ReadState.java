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

import java.util.concurrent.atomic.AtomicReference;

class ReadState
{
    private final AtomicReference<State> state = new AtomicReference<>(State.READING);

    boolean isReading()
    {
        return state.get() == State.READING;
    }

    boolean isSuspended()
    {
        State current = state.get();
        return current == State.SUSPENDED || current == State.EOF;
    }

    /**
     * Requests that reads from the connection be suspended when {@link #suspend()} is called.
     *
     * @return whether the suspending was successful
     */
    boolean suspending()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case READING:
                    if (state.compareAndSet(current, State.SUSPENDING))
                        return true;
                    break;
                case EOF:
                    return false;
                default:
                    throw new IllegalStateException(toString(current));
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
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case READING:
                    return false;
                case SUSPENDING:
                    if (state.compareAndSet(current, State.SUSPENDED))
                        return true;
                    break;
                case EOF:
                    return true;
                default:
                    throw new IllegalStateException(toString(current));
            }
        }
    }

    /**
     * @return true if reads from the connection were suspended and should now resume.
     */
    boolean resume()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case SUSPENDING:
                    if (state.compareAndSet(current, State.READING))
                        return false;
                    break;
                case SUSPENDED:
                    if (state.compareAndSet(current, State.READING))
                        return true;
                    break;
                case EOF:
                    return false;
                default:
                    throw new IllegalStateException(toString(current));
            }
        }
    }

    void eof()
    {
        state.set(State.EOF);
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
        EOF,
    }
}

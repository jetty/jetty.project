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

class Suspender
{
    private enum State
    {
        /** Not suspended. */
        NORMAL,

        /** Suspend requested but not yet taken effect. */
        PENDING,

        /** Suspended but resumable. */
        SUSPENDED,

        /** Permanently suspended (terminal state). */
        EOF,
    }

    private final AtomicReference<State> ref = new AtomicReference<>(State.NORMAL);

    boolean isSuspended()
    {
        State state = ref.get();
        return state == State.SUSPENDED || state == State.EOF;
    }

    /**
     * Requests that activity be suspended the next time {@link #activateRequestedSuspend()} is called.
     */
    void requestSuspend()
    {
        // Transition NORMAL -> PENDING
        State state;
        do
        {
            state = ref.get();
        }
        while (state == State.NORMAL && !ref.compareAndSet(state, State.PENDING));
    }

    /**
     * Returns true if activity is suspended, whether or not it was already suspended.
     */
    boolean activateRequestedSuspend()
    {
        // Transition PENDING -> SUSPENDED
        State state;
        do
        {
            state = ref.get();
        }
        while (state == State.PENDING && !ref.compareAndSet(state, State.SUSPENDED));
        return state != State.NORMAL;
    }

    /**
     * Returns true if activity was suspended and should now resume.
     */
    boolean requestResume()
    {
        // Transition PENDING|SUSPENDED -> NORMAL
        State state;
        do
        {
            state = ref.get();
        }
        while ((state == State.PENDING || state == State.SUSPENDED) && !ref.compareAndSet(state, State.NORMAL));
        return state == State.SUSPENDED;
    }

    void eof()
    {
        ref.set(State.EOF);
    }

    @Override
    public String toString()
    {
        return ref.get().toString();
    }
}

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

package org.eclipse.jetty.websocket.common;

import java.util.concurrent.atomic.AtomicReference;

public class AtomicClose
{
    enum State
    {
        /** No close handshake initiated (yet) */
        NONE,
        /** Local side initiated the close handshake */
        LOCAL,
        /** Remote side initiated the close handshake */
        REMOTE,
        /** An abnormal close situation (disconnect, timeout, etc...) */
        ABNORMAL
    }
    
    private final AtomicReference<State> state = new AtomicReference<>(State.NONE);
    
    public State get()
    {
        return state.get();
    }
    
    public boolean onAbnormal()
    {
        return state.compareAndSet(State.NONE, State.ABNORMAL);
    }
    
    public boolean onLocal()
    {
        return state.compareAndSet(State.NONE, State.LOCAL);
    }
    
    public boolean onRemote()
    {
        return state.compareAndSet(State.NONE, State.REMOTE);
    }
}

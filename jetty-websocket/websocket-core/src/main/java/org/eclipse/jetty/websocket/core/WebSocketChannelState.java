//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
public class WebSocketChannelState
{
    private static class State
    {
        final String name;
        final boolean inOpen;
        final boolean outOpen;
        final CloseStatus closeStatus;
        
        State(String name, boolean inOpen,boolean outOpen,CloseStatus closeStatus)
        {
            this.name = name;
            this.inOpen = inOpen;
            this.outOpen = outOpen;
            this.closeStatus = closeStatus;
        }
        
        @Override
        public String toString()
        {
            return String.format("%s{i=%b o=%b c=%d}",name,inOpen,outOpen,closeStatus==null?-1:closeStatus.getCode());
        }
    }
    
    private static final State CONNECTING = new State("CONNECTING",false,false,null);
    private static final State CONNECTED = new State("CONNECTED",true,true,null);
    private static final State OPEN = new State("OPEN",true,true,null);
    
    private AtomicReference<State> state = new AtomicReference<>(CONNECTING);
    
        
    public void onConnected()
    {
        if (!state.compareAndSet(CONNECTING, CONNECTED))
            throw new IllegalStateException(state.get().toString());
    }

    public void onOpen()
    {
        if (!state.compareAndSet(CONNECTED, OPEN))
            throw new IllegalStateException(state.get().toString());
    }
    
    @Override
    public String toString()
    {
        return String.format("%s[%s]", this.getClass().getSimpleName(), state.get());
    }

    // TODO remove
    @Deprecated
    public boolean isOpen()
    {
        State s = state.get();
        return s.inOpen || s.outOpen;
    }
    
    public boolean isClosed()
    {
        State s = state.get();
        return !s.inOpen && !s.outOpen;
    }
    
    public boolean isInOpen()
    {
        return state.get().inOpen;
    }

    public boolean isOutOpen()
    {
        return state.get().outOpen;
    }
    
    public CloseStatus getCloseStatus()
    {
        return state.get().closeStatus;
    }
    
    public boolean onCloseIn(CloseStatus closeStatus)
    {
        while(true)
        {
            State s = state.get();
            
            if (!s.inOpen)
                throw new IllegalStateException(state.get().toString());
            
            if (s.outOpen)
            {
                State closedIn = new State("OCLOSED",false,true,closeStatus);
                if (state.compareAndSet(s,closedIn))
                    return false;
            }
            else
            {
                State closed = new State("CLOSED",false,false,closeStatus);
                if (state.compareAndSet(s,closed))
                    return true;
            }
        }
    }

    public boolean onCloseOut(CloseStatus closeStatus)
    {
        while(true)
        {
            State s = state.get();
            
            if (!s.outOpen)
                throw new IllegalStateException(state.get().toString());
            
            if (s.inOpen)
            {
                State closedOut = new State("ICLOSED",true,false,closeStatus);
                if (state.compareAndSet(s,closedOut))
                    return false;
            }
            else
            {
                State closed = new State("CLOSED",false,false,closeStatus);
                if (state.compareAndSet(s,closed))
                    return true;
            }
        }
    }



}

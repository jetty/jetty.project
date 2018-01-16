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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;

/**
 * Interface for local WebSocket Endpoint Frame handling.
 */
public interface FrameHandler
{
    void setWebSocketChannel(Channel channel);
    default void onOpen() throws Exception {}
    default void onFrame(Frame frame, Callback callback) throws Exception {}
    default void onClosed(CloseStatus closeStatus) throws Exception {}
    default void onError(Throwable cause) throws Exception {}
    
    interface Channel extends OutgoingFrames
    {
        String getId();  // TODO this is here for now, but I'm not convinced it is a core concept
        
        String getSubprotocol();
        
        boolean isOpen(); // TODO is this needed and/or enough? what about half closed states?
        
        long getIdleTimeout(TimeUnit units);
        void setIdleTimeout(long timeout, TimeUnit units);
                
        void flush(); // TODO is this needed? 

        void close(Callback callback); // TODO is this needed?
        void close(int statusCode, String reason, Callback callback);
    }
}

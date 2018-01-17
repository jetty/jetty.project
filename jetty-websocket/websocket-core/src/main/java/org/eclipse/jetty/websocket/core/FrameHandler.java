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
    void onOpen(Channel channel) throws Exception;
    void onFrame(Frame frame, Callback callback) throws Exception;
    void onClosed(CloseStatus closeStatus) throws Exception;
    void onError(Throwable cause) throws Exception;
    
    interface Channel extends OutgoingFrames
    {        
        String getSubprotocol();
        
        boolean isOpen(); // TODO this checks that frames can be sent
        
        long getIdleTimeout(TimeUnit units);
        void setIdleTimeout(long timeout, TimeUnit units);
                
        void flushBatch(Callback callback);

        void close(Callback callback);
        void close(int statusCode, String reason, Callback callback);
    }
}

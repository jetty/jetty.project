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
    /**
     * Connection is being opened.
     * <p>
     *     FrameHandler can write during this call, but will not receive frames until
     *     the onOpen() completes.
     * </p>
     * @param channel the channel associated with this connection.
     * @throws Exception if unable to open. TODO: will close the connection (optionally choosing close status code based on WebSocketException type)?
     */
    void onOpen(Channel channel) throws Exception;

    /**
     * Receiver of all DATA Frames (Text, Binary, Continuation), and all CONTROL Frames (Ping, Pong, Close)

     * @param frame the raw frame
     * @param callback the callback to indicate success in processing frame (or failure)
     * @throws Exception if unable to process the frame.  TODO: will close the connection (optionally choosing close status code based on WebSocketException type)?
     */
    void onFrame(Frame frame, Callback callback) throws Exception;

    /**
     * This is the Close Handshake Complete event.
     * <p>
     *     The connection is now closed, no reading or writing is possible anymore.
     *     Implementations of FrameHandler can cleanup their resources for this connection now.
     * </p>
     *
     * @param closeStatus the close status received from remote, or in the case of abnormal closure from local.
     * @throws Exception if unable to complete the closure. TODO: what happens if an exception occurs here?
     */
    void onClosed(CloseStatus closeStatus) throws Exception;

    /**
     * An error has occurred or been detected in websocket-core and being reported to FrameHandler.
     *
     * @param cause the reason for the error
     * @throws Exception if unable to process the error. TODO: what hapens if an exception occurs here?  does any error means a connection is (or will be) closed?
     */
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

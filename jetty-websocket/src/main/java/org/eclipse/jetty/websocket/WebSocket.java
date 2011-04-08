// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.websocket;

import java.io.IOException;

/**
 * WebSocket Interface.
 * <p>
 * This interface provides the signature for a server-side end point of a websocket connection.
 * The Interface has several nested interfaces, for each type of message that may be received.
 */
public interface WebSocket
{   
    /**
     * Called when a new websocket connection is accepted.
     * @param connection The Connection object to use to send messages.
     */
    void onConnect(Connection connection);
    
    /**
     * Called when an established websocket connection closes
     * @param closeCode
     * @param message
     */
    void onDisconnect(int closeCode, String message);

    /**
     * A nested WebSocket interface for receiving text messages
     */
    interface OnTextMessage extends WebSocket
    {
        /**
         * Called with a complete text message when all fragments have been received.
         * The maximum size of text message that may be aggregated from multiple frames is set with {@link Connection#setMaxTextMessageSize(int)}.
         * @param data The message
         */
        void onMessage(String data);
    }

    /**
     * A nested WebSocket interface for receiving binary messages
     */
    interface OnBinaryMessage extends WebSocket
    {
        /**
         * Called with a complete binary message when all fragments have been received.
         * The maximum size of binary message that may be aggregated from multiple frames is set with {@link Connection#setMaxBinaryMessageSize(int)}.
         * @param data
         * @param offset
         * @param length
         */
        void onMessage(byte[] data, int offset, int length);
    }
    
    /**
     * A nested WebSocket interface for receiving control messages
     */
    interface OnControl extends WebSocket
    {
        /** 
         * Called when a control message has been received.
         * @param controlCode
         * @param data
         * @param offset
         * @param length
         * @return true if this call has completely handled the control message and no further processing is needed.
         */
        boolean onControl(byte controlCode,byte[] data, int offset, int length);
    }
    
    /**
     * A nested WebSocket interface for receiving any websocket frame
     */
    interface OnFrame extends WebSocket
    {
        /**
         * Called when any websocket frame is received.
         * @param flags
         * @param opcode
         * @param data
         * @param offset
         * @param length
         * @return true if this call has completely handled the frame and no further processing is needed (including aggregation and/or message delivery)
         */
        boolean onFrame(byte flags,byte opcode,byte[] data, int offset, int length);
    }
    
    public interface Connection
    {
        String getProtocol();
        void sendMessage(String data) throws IOException;
        void sendMessage(byte[] data, int offset, int length) throws IOException;
        void sendControl(byte control,byte[] data, int offset, int length) throws IOException;
        void sendFrame(byte flags,byte opcode,byte[] data, int offset, int length) throws IOException;
        void disconnect(int closeCode,String message);
        boolean isOpen();
        
        boolean isMore(byte flags);
        
        void setMaxTextMessageSize(int size);
        void setMaxBinaryMessageSize(int size);
        
        /**
         * Size in characters of the maximum text message to be received
         * @return <0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
         */
        int getMaxTextMessageSize();
        
        /**
         * Size in bytes of the maximum binary message to be received
         * @return <0 no aggregation of binary frames, >=0 size of binary frame aggregation buffer
         */
        int getMaxBinaryMessageSize();
    }
    
}

//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
    void onOpen(Connection connection);

    /**
     * Called when an established websocket connection closes
     * @param closeCode
     * @param message
     */
    void onClose(int closeCode, String message);

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
        
        void onHandshake(FrameConnection connection);
    }
    
    /**
     * A  Connection interface is passed to a WebSocket instance via the {@link WebSocket#onOpen(Connection)} to 
     * give the application access to the specifics of the current connection.   This includes methods 
     * for sending frames and messages as well as methods for interpreting the flags and opcodes of the connection.
     */
    public interface Connection
    {
        String getProtocol();
        void sendMessage(String data) throws IOException;
        void sendMessage(byte[] data, int offset, int length) throws IOException;
        
        /**
         * @deprecated Use {@link #close()}
         */
        void disconnect();

        /** 
         * Close the connection with normal close code.
         */
        void close();
        
        /** Close the connection with specific closeCode and message.
         * @param closeCode The close code to send, or -1 for no close code
         * @param message The message to send or null for no message
         */
        void close(int closeCode,String message);
        
        boolean isOpen();

        /**
         * @param ms The time in ms that the connection can be idle before closing
         */
        void setMaxIdleTime(int ms);
        
        /**
         * @param size size<0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
         */
        void setMaxTextMessageSize(int size);
        
        /**
         * @param size size<0 no aggregation of binary frames, >=0 size of binary frame aggregation buffer
         */
        void setMaxBinaryMessageSize(int size);
        
        /**
         * @return The time in ms that the connection can be idle before closing
         */
        int getMaxIdleTime();
        
        /**
         * Size in characters of the maximum text message to be received
         * @return size <0 No aggregation of frames to messages, >=0 max size of text frame aggregation buffer in characters
         */
        int getMaxTextMessageSize();
        
        /**
         * Size in bytes of the maximum binary message to be received
         * @return size <0 no aggregation of binary frames, >=0 size of binary frame aggregation buffer
         */
        int getMaxBinaryMessageSize();
    }

    /**
     * Frame Level Connection
     * <p>The Connection interface at the level of sending/receiving frames rather than messages.
     * Also contains methods to decode/generate flags and opcodes without using constants, so that 
     * code can be written to work with multiple drafts of the protocol.
     *
     */
    public interface FrameConnection extends Connection
    {
        /**
         * @return The opcode of a binary message
         */
        byte binaryOpcode();
        
        /**
         * @return The opcode of a text message
         */
        byte textOpcode();
        
        /**
         * @return The opcode of a continuation frame
         */
        byte continuationOpcode();
        
        /**
         * @return Mask for the FIN bit.
         */
        byte finMask();
        
        /** Set if frames larger than the frame buffer are handled with local fragmentations
         * @param allowFragmentation
         */
        void setAllowFrameFragmentation(boolean allowFragmentation);

        /**
         * @param flags The flags bytes of a frame
         * @return True of the flags indicate a final frame.
         */
        boolean isMessageComplete(byte flags);

        /**
         * @param opcode
         * @return True if the opcode is for a control frame
         */
        boolean isControl(byte opcode);

        /**
         * @param opcode
         * @return True if the opcode is for a text frame
         */
        boolean isText(byte opcode);

        /**
         * @param opcode
         * @return True if the opcode is for a binary frame
         */
        boolean isBinary(byte opcode);

        /**
         * @param opcode
         * @return True if the opcode is for a continuation frame
         */
        boolean isContinuation(byte opcode);

        /**
         * @param opcode 
         * @return True if the opcode is a close control
         */
        boolean isClose(byte opcode);

        /**
         * @param opcode
         * @return True if the opcode is a ping control
         */
        boolean isPing(byte opcode);

        /**
         * @param opcode
         * @return True if the opcode is a pong control
         */
        boolean isPong(byte opcode);
        
        /**
         * @return True if frames larger than the frame buffer are fragmented.
         */
        boolean isAllowFrameFragmentation();
        
        /** Send a control frame
         * @param control
         * @param data
         * @param offset
         * @param length
         * @throws IOException
         */
        void sendControl(byte control,byte[] data, int offset, int length) throws IOException;

        /** Send an arbitrary frame
         * @param flags
         * @param opcode
         * @param data
         * @param offset
         * @param length
         * @throws IOException
         */
        void sendFrame(byte flags,byte opcode,byte[] data, int offset, int length) throws IOException;
    }
}

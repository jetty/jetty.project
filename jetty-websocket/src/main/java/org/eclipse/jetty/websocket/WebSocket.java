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

public interface WebSocket
{   
    void onConnect(Connection outbound);
    void onDisconnect(int closeCode, String message);

    interface OnTextMessage extends WebSocket
    {
        void onMessage(String data);
    }
    
    interface OnBinaryMessage extends WebSocket
    {
        void onMessage(byte[] data, int offset, int length);
    }
    
    interface OnControl extends WebSocket
    {
        boolean onControl(byte controlCode,byte[] data, int offset, int length);
    }
    
    interface OnFrame extends WebSocket
    {
        boolean onFrame(byte flags,byte opcode,byte[] data, int offset, int length);
    }
    
    public interface Connection
    {
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

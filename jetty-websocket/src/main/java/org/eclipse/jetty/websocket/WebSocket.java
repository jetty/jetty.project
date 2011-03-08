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
    @Deprecated
    public final byte LENGTH_FRAME=(byte)0x80;
    @Deprecated
    public final byte SENTINEL_FRAME=(byte)0x00;

    public final static byte   OP_CONTINUATION = 0x00;
    public final static byte   OP_CLOSE = 0x01;
    public final static byte   OP_PING = 0x02;
    public final static byte   OP_PONG = 0x03;
    public final static byte   OP_TEXT = 0x04;
    public final static byte   OP_BINARY = 0x05;
    
    public final static int CLOSE_NORMAL=1000;
    public final static int CLOSE_SHUTDOWN=1001;
    public final static int CLOSE_PROTOCOL=1002;
    public final static int CLOSE_DATA=1003;
    public final static int CLOSE_LARGE=1004;
    
    void onConnect(Outbound outbound);
    void onMessage(byte opcode,String data);
    void onFragment(boolean more,byte opcode,byte[] data, int offset, int length);
    void onMessage(byte opcode,byte[] data, int offset, int length);
    void onDisconnect(); // TODO add code 
    
    public interface Outbound
    {
        void sendMessage(String data) throws IOException;
        void sendMessage(byte opcode,String data) throws IOException;
        void sendMessage(byte opcode,byte[] data, int offset, int length) throws IOException;
        void sendFragment(boolean more,byte opcode,byte[] data, int offset, int length) throws IOException;
        void disconnect();
        void disconnect(int code,String message);
        boolean isOpen();
    }
}

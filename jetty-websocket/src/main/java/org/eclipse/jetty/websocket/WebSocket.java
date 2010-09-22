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
    public final byte LENGTH_FRAME=(byte)0x80;
    public final byte SENTINEL_FRAME=(byte)0x00;
    void onConnect(Outbound outbound);
    void onMessage(byte opcode,String data);
    void onFragment(boolean more,byte opcode,byte[] data, int offset, int length);
    void onMessage(byte opcode,byte[] data, int offset, int length);
    void onDisconnect();
    
    public interface Outbound
    {
        void sendMessage(String data) throws IOException;
        void sendMessage(byte opcode,String data) throws IOException;
        void sendMessage(byte opcode,byte[] data, int offset, int length) throws IOException;
        void sendFragment(boolean more,byte opcode,byte[] data, int offset, int length) throws IOException;
        void disconnect();
        boolean isOpen();
    }
}

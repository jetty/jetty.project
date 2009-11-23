package org.eclipse.jetty.websocket;

import java.io.IOException;

public interface WebSocket
{
    public final byte LENGTH_FRAME=(byte)0x80;
    public final byte SENTINEL_FRAME=(byte)0x00;
    void onConnect(Outbound outbound);
    void onMessage(byte frame,String data);
    void onMessage(byte frame,byte[] data, int offset, int length);
    void onDisconnect();
    
    public interface Outbound
    {
        void sendMessage(byte frame,String data) throws IOException;
        void sendMessage(byte frame,byte[] data) throws IOException;
        void sendMessage(byte frame,byte[] data, int offset, int length) throws IOException;
        void disconnect();
        boolean isOpen();
    }
}

package org.eclipse.jetty.websocket;


import java.util.List;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.nio.AsyncConnection;

public interface WebSocketConnection extends AsyncConnection
{
    void fillBuffersFrom(Buffer buffer);
    
    List<Extension> getExtensions();
    
    WebSocket.Connection getConnection();
}
package org.eclipse.jetty.websocket;


import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.nio.AsyncConnection;

public interface WebSocketConnection extends AsyncConnection
{
    void fillBuffersFrom(Buffer buffer);
    
    void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException;
    
    List<Extension> getExtensions();
    
    WebSocket.Connection getConnection();
}
package org.eclipse.jetty.websocket;


import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;

public interface WebSocketConnection extends Connection
{
    void fillBuffersFrom(Buffer buffer);
    
    void handshake(HttpServletRequest request, HttpServletResponse response, String origin, String subprotocol) throws IOException;
    
    List<Extension> getExtensions();
    
    WebSocket.Connection getConnection();
}
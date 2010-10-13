package org.eclipse.jetty.websocket;


import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;

public interface WebSocketConnection extends Connection, WebSocket.Outbound
{
    void fillBuffersFrom(Buffer buffer);
    
    void handshake(HttpServletRequest request, HttpServletResponse response, String origin, String subprotocol) throws IOException;
}
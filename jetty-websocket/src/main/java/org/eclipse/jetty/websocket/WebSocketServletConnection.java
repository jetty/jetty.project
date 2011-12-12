package org.eclipse.jetty.websocket;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public interface WebSocketServletConnection extends WebSocketConnection
{
    void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException;
}

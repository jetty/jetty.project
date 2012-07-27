package org.eclipse.jetty.websocket.server.helper;

import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

@SuppressWarnings("serial")
public class RFCServlet extends WebSocketServlet
{
    @Override
    public void registerWebSockets(WebSocketServerFactory factory)
    {
        factory.register(RFCSocket.class);
    }
}
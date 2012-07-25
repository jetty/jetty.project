package org.eclipse.jetty.websocket.server.helper;

import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

/**
 * Initialize a simple Echo websocket
 */
@SuppressWarnings("serial")
public class EchoServlet extends WebSocketServlet
{
    @Override
    public void registerWebSockets(WebSocketServerFactory factory)
    {
        factory.register(EchoSocket.class);
    }
}

package org.eclipse.jetty.websocket.server.examples;

import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

/**
 * Example servlet for most basic form.
 */
@SuppressWarnings("serial")
public class MyEchoServlet extends WebSocketServlet
{
    @Override
    public void registerWebSockets(WebSocketServerFactory factory)
    {
        factory.register(MyEchoSocket.class);
    }
}

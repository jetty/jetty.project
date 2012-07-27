package org.eclipse.jetty.websocket.server.ab;

import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

/**
 * Servlet with bigger message policy sizes, with registered simple echo socket.
 */
@SuppressWarnings("serial")
public class ABServlet extends WebSocketServlet
{
    private static final int KBYTE = 1024;
    private static final int MBYTE = KBYTE * KBYTE;

    @Override
    public void registerWebSockets(WebSocketServerFactory factory)
    {
        factory.register(ABSocket.class);

        factory.getPolicy().setBufferSize(2 * MBYTE);
        factory.getPolicy().setMaxTextMessageSize(2 * MBYTE);
        factory.getPolicy().setMaxBinaryMessageSize(2 * MBYTE);
    }
}

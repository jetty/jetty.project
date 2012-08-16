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
        // Test cases 9.x uses BIG frame sizes, let policy handle them.
        int bigFrameSize = 20 * MBYTE;

        factory.getPolicy().setBufferSize(bigFrameSize);
        factory.getPolicy().setMaxPayloadSize(bigFrameSize);
        factory.getPolicy().setMaxTextMessageSize(bigFrameSize);
        factory.getPolicy().setMaxBinaryMessageSize(bigFrameSize);

        factory.register(ABSocket.class);
    }
}

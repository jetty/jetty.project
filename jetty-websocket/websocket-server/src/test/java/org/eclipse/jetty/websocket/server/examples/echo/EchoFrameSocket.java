package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;

import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.BaseFrame;

@WebSocket
public class EchoFrameSocket
{
    @OnWebSocketFrame
    public void onFrame(WebSocketConnection conn, BaseFrame frame)
    {
        if (!conn.isOpen())
        {
            return;
        }

        try
        {
            conn.write(frame);
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}

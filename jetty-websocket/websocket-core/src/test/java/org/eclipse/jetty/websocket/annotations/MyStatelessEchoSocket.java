package org.eclipse.jetty.websocket.annotations;

import java.io.IOException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Example of a stateless websocket implementation.
 * <p>
 * Useful for websockets that only reply to incoming requests.
 * <p>
 * Note: that for this style of websocket to be viable on the server side be sure that you only create 1 instance of this socket, as more instances would be
 * wasteful of resources and memory.
 */
@WebSocket
public class MyStatelessEchoSocket
{
    @OnWebSocketMessage
    public void onText(WebSocketConnection conn, String text)
    {
        try
        {
            conn.write(null,new FutureCallback<Void>(),text);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

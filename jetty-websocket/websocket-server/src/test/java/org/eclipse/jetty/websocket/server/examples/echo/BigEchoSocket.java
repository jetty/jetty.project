package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Example Socket for echoing back Big data using the Annotation techniques along with stateless techniques.
 */
@WebSocket(maxTextSize = 64 * 1024, maxBinarySize = 64 * 1024)
public class BigEchoSocket
{
    @OnWebSocketMessage
    public void onBinary(WebSocketConnection conn, byte buf[], int offset, int length)
    {
        if (conn.isOpen())
        {
            return;
        }
        try
        {
            conn.write(null,new FutureCallback<Void>(),buf,offset,length);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @OnWebSocketMessage
    public void onText(WebSocketConnection conn, String message)
    {
        if (conn.isOpen())
        {
            return;
        }
        try
        {
            conn.write(null,new FutureCallback<Void>(),message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

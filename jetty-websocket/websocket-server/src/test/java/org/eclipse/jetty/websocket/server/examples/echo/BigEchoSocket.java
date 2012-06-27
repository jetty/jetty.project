package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.annotations.OnWebSocketBinary;
import org.eclipse.jetty.websocket.annotations.OnWebSocketText;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Example Socket for echoing back Big data using the Annotation techniques along with stateless techniques.
 */
@WebSocket(maxTextSize = 64 * 1024, maxBinarySize = 64 * 1024)
public class BigEchoSocket
{
    @OnWebSocketBinary
    public void onBinary(WebSocketConnection conn, ByteBuffer buffer)
    {
        if (conn.isOpen())
        {
            return;
        }
        try
        {
            buffer.flip(); // flip the incoming buffer to write mode
            conn.write(buffer);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @OnWebSocketText
    public void onText(WebSocketConnection conn, String message)
    {
        if (conn.isOpen())
        {
            return;
        }
        try
        {
            conn.write(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

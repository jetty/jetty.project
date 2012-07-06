package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class EchoBroadcastSocket
{
    private static final ConcurrentLinkedQueue<EchoBroadcastSocket> BROADCAST = new ConcurrentLinkedQueue<EchoBroadcastSocket>();

    protected WebSocketConnection conn;

    @OnWebSocketMessage
    public void onBinary(byte buf[], int offset, int len)
    {
        for (EchoBroadcastSocket sock : BROADCAST)
        {
            try
            {
                sock.conn.write(null,new FutureCallback<Void>(),buf,offset,len);
            }
            catch (IOException e)
            {
                BROADCAST.remove(sock);
                e.printStackTrace();
            }
        }
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        BROADCAST.remove(this);
    }

    @OnWebSocketConnect
    public void onOpen(WebSocketConnection conn)
    {
        this.conn = conn;
        BROADCAST.add(this);
    }

    @OnWebSocketMessage
    public void onText(String text)
    {
        for (EchoBroadcastSocket sock : BROADCAST)
        {
            try
            {
                sock.conn.write(null,new FutureCallback<Void>(),text);
            }
            catch (IOException e)
            {
                BROADCAST.remove(sock);
                e.printStackTrace();
            }
        }
    }
}

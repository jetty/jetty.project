package org.eclipse.jetty.websocket.server.examples;

import java.io.IOException;

import org.eclipse.jetty.websocket.WebSocket;

public class MyEchoSocket implements WebSocket, WebSocket.OnTextMessage
{
    private Connection conn;

    @Override
    public void onClose(int closeCode, String message)
    {
        /* do nothing */
    }

    @Override
    public void onMessage(String data)
    {
        try
        {
            // echo the data back
            conn.sendMessage(data);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onOpen(Connection connection)
    {
        this.conn = connection;
    }
}

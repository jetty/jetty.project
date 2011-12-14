package org.eclipse.jetty.websocket.helper;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.WebSocket;

public class CaptureSocket implements WebSocket, WebSocket.OnTextMessage
{
    private Connection conn;
    public List<String> messages;

    public CaptureSocket()
    {
        messages = new ArrayList<String>();
    }

    public boolean isConnected()
    {
        if (conn == null)
        {
            return false;
        }
        return conn.isOpen();
    }

    public void onMessage(String data)
    {
        System.out.printf("Received Message \"%s\" [size %d]%n", data, data.length());
        messages.add(data);
    }

    public void onOpen(Connection connection)
    {
        this.conn = connection;
    }

    public void onClose(int closeCode, String message)
    {
        this.conn = null;
    }
}
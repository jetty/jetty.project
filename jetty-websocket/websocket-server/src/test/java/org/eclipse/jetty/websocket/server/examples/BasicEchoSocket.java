package org.eclipse.jetty.websocket.server.examples;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;

public class BasicEchoSocket extends WebSocketAdapter
{
    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (isNotConnected())
        {
            return;
        }
        try
        {
            getConnection().write(payload,offset,len);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (isNotConnected())
        {
            return;
        }
        try
        {
            getConnection().write(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

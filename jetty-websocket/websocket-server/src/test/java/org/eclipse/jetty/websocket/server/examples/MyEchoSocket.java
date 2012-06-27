package org.eclipse.jetty.websocket.server.examples;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;

public class MyEchoSocket extends WebSocketAdapter
{
    @Override
    public void onWebSocketText(String message)
    {
        if (isNotConnected())
        {
            return;
        }

        try
        {
            // echo the data back
            getConnection().write(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

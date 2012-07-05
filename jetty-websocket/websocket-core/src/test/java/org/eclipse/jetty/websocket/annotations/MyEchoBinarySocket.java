package org.eclipse.jetty.websocket.annotations;

import java.io.IOException;

/**
 * Test of constructing a new WebSocket based on a base class
 */
@WebSocket
public class MyEchoBinarySocket extends MyEchoSocket
{
    @OnWebSocketMessage
    public void echoBin(byte buf[], int offset, int length)
    {
        try
        {
            getConnection().write(buf,offset,length);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

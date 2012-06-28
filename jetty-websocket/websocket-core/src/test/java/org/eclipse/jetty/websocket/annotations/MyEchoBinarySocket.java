package org.eclipse.jetty.websocket.annotations;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Test of constructing a new WebSocket based on a base class
 */
@WebSocket
public class MyEchoBinarySocket extends MyEchoSocket
{
    @OnWebSocketBinary
    public void echoBin(ByteBuffer payload)
    {
        try
        {
            getConnection().write(payload);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}

package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.protocol.Frame;

/**
 * Echo back the incoming text or binary as 2 frames of (roughly) equal size.
 */
@WebSocket
public class EchoFragmentSocket
{
    @OnWebSocketFrame
    public void onFrame(WebSocketConnection conn, Frame frame)
    {
        if (!frame.getOpCode().isDataFrame())
        {
            return;
        }

        byte data[] = frame.getPayloadData();
        int half = data.length / 2;

        Callback<Void> nop = new FutureCallback<>();
        try
        {
            switch (frame.getOpCode())
            {
                case BINARY:
                    conn.write(null,nop,data,0,half);
                    conn.write(null,nop,data,half,data.length - half);
                    break;
                case TEXT:
                    conn.write(null,nop,new String(data,0,half,StringUtil.__UTF8_CHARSET));
                    conn.write(null,nop,new String(data,half,data.length - half,StringUtil.__UTF8_CHARSET));
                    break;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}

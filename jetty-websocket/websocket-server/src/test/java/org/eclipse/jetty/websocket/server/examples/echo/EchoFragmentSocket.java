package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.DataFrame;

/**
 * Echo back the incoming text or binary as 2 frames of (roughly) equal size.
 */
@WebSocket
public class EchoFragmentSocket
{
    @OnWebSocketFrame
    public void onFrame(WebSocketConnection conn, DataFrame data)
    {
        ByteBuffer payload = data.getPayload();
        BufferUtil.flipToFlush(payload,0);
        int half = payload.remaining() / 2;

        ByteBuffer buf1 = payload.slice();
        ByteBuffer buf2 = payload.slice();

        buf1.limit(half);
        buf2.position(half);

        DataFrame d1 = new DataFrame(data.getOpCode());
        d1.setFin(false);
        d1.setPayload(buf1);

        DataFrame d2 = new DataFrame(data.getOpCode());
        d2.setFin(true);
        d2.setPayload(buf2);

        Callback<Void> nop = new FutureCallback<>();
        try
        {
            conn.write(null,nop,d1,d2);
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}

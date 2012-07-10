package org.eclipse.jetty.websocket.server.examples.echo;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
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

        ByteBuffer data = frame.getPayload();

        int half = data.remaining() / 2;

        ByteBuffer buf1 = data.slice();
        ByteBuffer buf2 = data.slice();

        buf1.limit(half);
        buf2.position(half);

        Callback<Void> nop = new FutureCallback<>();
        try
        {
            switch (frame.getOpCode())
            {
                case BINARY:
                    conn.write(null,nop,buf1);
                    conn.write(null,nop,buf2);
                    break;
                case TEXT:
                    // NOTE: This impl is not smart enough to split on a UTF8 boundary
                    conn.write(null,nop,BufferUtil.toUTF8String(buf1));
                    conn.write(null,nop,BufferUtil.toUTF8String(buf2));
                    break;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}

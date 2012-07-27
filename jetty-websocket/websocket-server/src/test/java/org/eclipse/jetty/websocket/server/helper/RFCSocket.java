package org.eclipse.jetty.websocket.server.helper;

import java.io.IOException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class RFCSocket
{
    private static Logger LOG = Log.getLogger(RFCSocket.class);

    private WebSocketConnection conn;

    @OnWebSocketMessage
    public void onBinary(byte buf[], int offset, int len)
    {
        LOG.debug("onBinary(byte[{}],{},{})",buf.length,offset,len);

        // echo the message back.
        try
        {
            this.conn.write(null,new FutureCallback<Void>(),buf,offset,len);
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }

    @OnWebSocketConnect
    public void onOpen(WebSocketConnection conn)
    {
        this.conn = conn;
    }

    @OnWebSocketMessage
    public void onText(String message)
    {
        LOG.debug("onText({})",message);
        // Test the RFC 6455 close code 1011 that should close
        // trigger a WebSocket server terminated close.
        if (message.equals("CRASH"))
        {
            throw new RuntimeException("Something bad happened");
        }

        // echo the message back.
        try
        {
            this.conn.write(null,new FutureCallback<Void>(),message);
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}
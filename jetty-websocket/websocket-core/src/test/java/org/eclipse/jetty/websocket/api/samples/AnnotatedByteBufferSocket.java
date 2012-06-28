package org.eclipse.jetty.websocket.api.samples;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.annotations.OnWebSocketBinary;
import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.EventCapture;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class AnnotatedByteBufferSocket
{
    public EventCapture capture = new EventCapture();

    @OnWebSocketBinary
    public void onBinary(ByteBuffer payload)
    {
        capture.add("onBinary(%s)",payload);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        capture.add("onClose(%d, %s)",statusCode,capture.q(reason));
    }

    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        capture.add("onConnect(%s)", conn);
    }

}

package org.eclipse.jetty.websocket.api.samples;

import java.io.InputStream;

import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.EventCapture;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class AnnotatedBinaryStreamSocket
{
    public EventCapture capture = new EventCapture();

    @OnWebSocketMessage
    public void onBinary(InputStream stream)
    {
        capture.add("onBinary(%s)",stream);
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

package org.eclipse.jetty.websocket.api.samples;

import java.io.InputStream;
import java.io.Reader;

import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.EventCapture;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class AnnotatedStreamingSocket
{
    public EventCapture capture = new EventCapture();

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        capture.add("onClose(%d, %s)",statusCode,capture.q(reason));
    }

    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        capture.add("onConnect(%s)",conn);
    }

    @OnWebSocketFrame
    public void onFrame(Frame frame)
    {
    }

    // Binary
    @OnWebSocketMessage
    public void onMessage(byte buf[], int offset, int length)
    {
    }

    // Binary
    @OnWebSocketMessage
    public void onMessage(InputStream stream)
    {
    }

    // Text
    @OnWebSocketMessage
    public void onMessage(Reader stream)
    {
    }

    // Text
    @OnWebSocketMessage
    public void onMessage(String message)
    {
    }

}

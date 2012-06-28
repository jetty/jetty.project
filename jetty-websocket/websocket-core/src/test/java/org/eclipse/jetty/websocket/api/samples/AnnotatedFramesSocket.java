package org.eclipse.jetty.websocket.api.samples;

import org.eclipse.jetty.websocket.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.EventCapture;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.ControlFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;

@WebSocket
public class AnnotatedFramesSocket
{
    public EventCapture capture = new EventCapture();

    @OnWebSocketFrame
    public void onBaseFrame(BaseFrame frame)
    {
        capture.add("onBaseFrame(%s)",frame);
    }

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
    public void onControlFrame(ControlFrame ping)
    {
        capture.add("onControlFrame(%s)",ping);
    }

    @OnWebSocketFrame
    public void onPing(PingFrame ping)
    {
        capture.add("onPingFrame(%s)",ping);
    }

    @OnWebSocketFrame
    public void onTextFrame(TextFrame text)
    {
        capture.add("onTextFrame(%s)",text);
    }
}

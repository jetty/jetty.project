package org.eclipse.jetty.websocket.api.samples;

import org.eclipse.jetty.websocket.api.EventCapture;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;

public class ListenerBasicSocket implements WebSocketListener
{
    public EventCapture capture = new EventCapture();

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        capture.add("onWebSocketBinary([%d], %d, %d)",payload.length,offset,len);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        capture.add("onWebSocketClose(%d, %s)",statusCode,capture.q(reason));
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        capture.add("onWebSocketConnect(%s)",connection);
    }

    @Override
    public void onWebSocketException(WebSocketException error)
    {
        capture.add("onWebSocketException((%s) %s)",error.getClass().getSimpleName(),error.getMessage());
    }

    @Override
    public void onWebSocketText(String message)
    {
        capture.add("onWebSocketText(%s)",capture.q(message));
    }
}

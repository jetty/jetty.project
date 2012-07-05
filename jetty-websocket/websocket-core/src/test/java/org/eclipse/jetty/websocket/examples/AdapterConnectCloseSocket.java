package org.eclipse.jetty.websocket.examples;

import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.driver.EventCapture;

public class AdapterConnectCloseSocket extends WebSocketAdapter
{
    public EventCapture capture = new EventCapture();

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
}

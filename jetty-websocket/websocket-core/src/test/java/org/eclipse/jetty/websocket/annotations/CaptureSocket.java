package org.eclipse.jetty.websocket.annotations;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

@WebSocket
public class CaptureSocket
{
    private List<String> events = new ArrayList<>();

    private void addEvent(String format, Object ... args)
    {
        events.add(String.format(format,args));
    }

    public void clear()
    {
        events.clear();
    }

    public List<String> getEvents()
    {
        return events;
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        addEvent("OnWebSocketClose(%d, %s)",statusCode,qoute(reason));
    }

    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        addEvent("OnWebSocketConnect(conn)");
    }

    @OnWebSocketText
    public void onText(String message) {
        addEvent("@OnWebSocketText(%s)", qoute(message));
    }

    private String qoute(String str)
    {
        if (str == null)
        {
            return "<null>";
        }
        return '"' + str + '"';
    }
}

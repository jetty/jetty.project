package org.eclipse.jetty.websocket.server.examples;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.server.WebSocketServlet;

/**
 * Example servlet for most basic form.
 */
@SuppressWarnings("serial")
public class MyEchoServlet extends WebSocketServlet
{
    @Override
    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
    {
        return new MyEchoSocket();
    }
}

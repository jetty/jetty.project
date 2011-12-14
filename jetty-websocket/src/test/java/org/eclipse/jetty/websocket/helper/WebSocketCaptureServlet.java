package org.eclipse.jetty.websocket.helper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketServlet;

@SuppressWarnings("serial") 
public class WebSocketCaptureServlet extends WebSocketServlet
{
    public List<CaptureSocket> captures = new ArrayList<CaptureSocket>();;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        resp.sendError(404);
    }

    public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
    {
        CaptureSocket capture = new CaptureSocket();
        captures.add(capture);
        return capture;
    }
}
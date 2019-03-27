package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.websocket.servlet.FrameHandlerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public abstract class JettyWebSocketServlet extends WebSocketServlet
{
    protected abstract void configure(JettyWebSocketServletFactory factory);

    @Override
    protected final void configure(WebSocketServletFactory factory)
    {
        configure(new JettyWebSocketServletFactory(factory));
    }

    @Override
    public FrameHandlerFactory getFactory()
    {
        JettyServerFrameHandlerFactory frameHandlerFactory = JettyServerFrameHandlerFactory.getFactory(getServletContext());

        if (frameHandlerFactory==null)
            throw new IllegalStateException("JettyServerFrameHandlerFactory not found");

        return frameHandlerFactory;
    }
}

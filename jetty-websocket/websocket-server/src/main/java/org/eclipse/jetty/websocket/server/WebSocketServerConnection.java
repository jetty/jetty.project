package org.eclipse.jetty.websocket.server;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.io.AbstractWebSocketConnection;

public class WebSocketServerConnection extends AbstractWebSocketConnection
{
    private final WebSocketServerFactory factory;
    private boolean connected;

    public WebSocketServerConnection(EndPoint endp, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy,
            ByteBufferPool bufferPool, WebSocketServerFactory factory)
    {
        super(endp,executor,scheduler,policy,bufferPool);
        this.factory = factory;
        this.connected = false;
    }

    @Override
    public void onClose()
    {
        super.onClose();
        factory.sessionClosed(getSession());
    }

    @Override
    public void onOpen()
    {
        if (!connected)
        {
            factory.sessionOpened(getSession());
            connected = true;
        }
        super.onOpen();
    }
}

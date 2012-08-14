package org.eclipse.jetty.websocket.client.io;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient.ConnectFuture;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.io.AbstractWebSocketConnection;

public class WebSocketClientConnection extends AbstractWebSocketConnection
{
    private final WebSocketClientFactory factory;
    private final ConnectFuture connectFuture;

    public WebSocketClientConnection(EndPoint endp, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy, ByteBufferPool bufferPool,
            WebSocketClientFactory factory, ConnectFuture confut)
    {
        super(endp,executor,scheduler,policy,bufferPool);
        this.factory = factory;
        this.connectFuture = confut;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();

        // TODO: Handshake handshake = new WebSocket13Handshake(this);
        // TODO: getExecutor().execute(handshake);
    }
}

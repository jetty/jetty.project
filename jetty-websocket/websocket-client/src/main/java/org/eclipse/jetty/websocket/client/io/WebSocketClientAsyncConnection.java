package org.eclipse.jetty.websocket.client.io;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.io.WebSocketAsyncConnection;

public class WebSocketClientAsyncConnection extends WebSocketAsyncConnection
{
    private final WebSocketClientFactory factory;

    public WebSocketClientAsyncConnection(AsyncEndPoint endp, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy,
            ByteBufferPool bufferPool, WebSocketClientFactory factory)
    {
        super(endp,executor,scheduler,policy,bufferPool);
        this.factory = factory;
    }
}

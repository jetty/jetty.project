package org.eclipse.jetty.websocket.server;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;

public class AsyncWebSocketConnection extends AbstractAsyncConnection
{
    // TODO: track extensions? (only those that need to operate at this level?)
    // TODO: track generic WebSocket.Connection (for API access)?

    public AsyncWebSocketConnection(AsyncEndPoint endp, Executor executor)
    {
        super(endp,executor);
    }

    public AsyncWebSocketConnection(AsyncEndPoint endp, Executor executor, boolean executeOnlyFailure)
    {
        super(endp,executor,executeOnlyFailure);
    }

    @Override
    public void onFillable()
    {
        // TODO Auto-generated method stub

    }
}

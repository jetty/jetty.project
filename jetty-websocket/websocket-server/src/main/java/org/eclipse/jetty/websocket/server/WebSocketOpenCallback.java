package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.util.FutureCallback;

public class WebSocketOpenCallback extends FutureCallback<String>
{
    @Override
    public void completed(String context)
    {
        // TODO notify API on connection open
    }

    @Override
    public void failed(String context, Throwable x)
    {
        // TODO notify API on open failure
    }
}

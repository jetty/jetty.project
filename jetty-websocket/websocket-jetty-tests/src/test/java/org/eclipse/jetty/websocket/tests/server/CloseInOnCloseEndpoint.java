package org.eclipse.jetty.websocket.tests.server;

import org.eclipse.jetty.websocket.api.StatusCode;

public class CloseInOnCloseEndpoint extends AbstractCloseEndpoint
{
    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        getSession().close(StatusCode.SERVER_ERROR, "this should be a noop");
        super.onWebSocketClose(statusCode, reason);
    }
}

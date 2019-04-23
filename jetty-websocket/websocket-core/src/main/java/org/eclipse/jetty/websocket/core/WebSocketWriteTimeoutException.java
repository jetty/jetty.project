package org.eclipse.jetty.websocket.core;

public class WebSocketWriteTimeoutException extends WebSocketTimeoutException
{
    public WebSocketWriteTimeoutException(String message)
    {
        super(message);
    }
}

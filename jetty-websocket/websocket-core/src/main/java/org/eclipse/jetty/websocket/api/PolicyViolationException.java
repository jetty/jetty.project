package org.eclipse.jetty.websocket.api;

@SuppressWarnings("serial")
public class PolicyViolationException extends CloseException
{
    public PolicyViolationException(String message)
    {
        super(WebSocket.CLOSE_POLICY_VIOLATION,message);
    }

    public PolicyViolationException(String message, Throwable t)
    {
        super(WebSocket.CLOSE_POLICY_VIOLATION,message,t);
    }

    public PolicyViolationException(Throwable t)
    {
        super(WebSocket.CLOSE_POLICY_VIOLATION,t);
    }
}

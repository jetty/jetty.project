package org.eclipse.jetty.websocket.api;

/**
 * Exception when a violation of policy occurs and should trigger a connection close.
 * 
 * @see StatusCode#POLICY_VIOLATION
 */
@SuppressWarnings("serial")
public class PolicyViolationException extends CloseException
{
    public PolicyViolationException(String message)
    {
        super(StatusCode.POLICY_VIOLATION,message);
    }

    public PolicyViolationException(String message, Throwable t)
    {
        super(StatusCode.POLICY_VIOLATION,message,t);
    }

    public PolicyViolationException(Throwable t)
    {
        super(StatusCode.POLICY_VIOLATION,t);
    }
}

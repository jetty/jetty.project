package org.eclipse.jetty.websocket.api;

/**
 * Exception when a message is too large for the internal buffers occurs and should trigger a connection close.
 * 
 * @see StatusCode#MESSAGE_TOO_LARGE
 */
@SuppressWarnings("serial")
public class MessageTooLargeException extends CloseException
{
    public MessageTooLargeException(String message)
    {
        super(StatusCode.MESSAGE_TOO_LARGE,message);
    }

    public MessageTooLargeException(String message, Throwable t)
    {
        super(StatusCode.MESSAGE_TOO_LARGE,message,t);
    }

    public MessageTooLargeException(Throwable t)
    {
        super(StatusCode.MESSAGE_TOO_LARGE,t);
    }
}

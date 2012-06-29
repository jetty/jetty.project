package org.eclipse.jetty.websocket.api;

@SuppressWarnings("serial")
public class CloseException extends WebSocketException
{
    private short statusCode;

    public CloseException(short closeCode, String message)
    {
        super(message);
        this.statusCode = closeCode;
    }

    public CloseException(short closeCode, String message, Throwable cause)
    {
        super(message,cause);
        this.statusCode = closeCode;
    }

    public CloseException(short closeCode, Throwable cause)
    {
        super(cause);
        this.statusCode = closeCode;
    }

    public short getStatusCode()
    {
        return statusCode;
    }

}

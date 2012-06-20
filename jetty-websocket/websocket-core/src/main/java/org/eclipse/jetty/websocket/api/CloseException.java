package org.eclipse.jetty.websocket.api;

@SuppressWarnings("serial")
public class CloseException extends WebSocketException
{
    private short closeCode;

    public CloseException(short closeCode, String message)
    {
        super(message);
        this.closeCode = closeCode;
    }

    public CloseException(short closeCode, String message, Throwable cause)
    {
        super(message,cause);
        this.closeCode = closeCode;
    }

    public CloseException(short closeCode, Throwable cause)
    {
        super(cause);
        this.closeCode = closeCode;
    }

    public short getCloseCode()
    {
        return closeCode;
    }

}

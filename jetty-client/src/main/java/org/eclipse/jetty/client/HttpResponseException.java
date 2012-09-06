package org.eclipse.jetty.client;

public class HttpResponseException extends RuntimeException
{
    public HttpResponseException()
    {
    }

    public HttpResponseException(String message)
    {
        super(message);
    }

    public HttpResponseException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public HttpResponseException(Throwable cause)
    {
        super(cause);
    }

    public HttpResponseException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace)
    {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}

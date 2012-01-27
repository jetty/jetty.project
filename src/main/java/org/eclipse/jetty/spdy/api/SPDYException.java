package org.eclipse.jetty.spdy.api;

/**
 * <p>An unrecoverable exception that signals to the application that
 * something wrong happened</p>
 */
public class SPDYException extends RuntimeException
{
    public SPDYException()
    {
    }

    public SPDYException(String message)
    {
        super(message);
    }

    public SPDYException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public SPDYException(Throwable cause)
    {
        super(cause);
    }

    public SPDYException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace)
    {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.SessionStatus;

public class SessionException extends Exception
{
    private final SessionStatus sessionStatus;

    public SessionException(SessionStatus sessionStatus)
    {
        this.sessionStatus = sessionStatus;
    }

    public SessionException(SessionStatus sessionStatus, String message)
    {
        super(message);
        this.sessionStatus = sessionStatus;
    }

    public SessionException(SessionStatus sessionStatus, Throwable cause)
    {
        super(cause);
        this.sessionStatus = sessionStatus;
    }
}

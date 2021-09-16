package org.eclipse.jetty.http3.internal.parser;

public class ParseException extends Exception
{
    private final int error;
    private final boolean fatal;

    public ParseException(int error, String message)
    {
        this(error, message, false);
    }

    public ParseException(int error, String message, boolean fatal)
    {
        this(error, message, fatal, null);
    }

    public ParseException(int error, String message, boolean fatal, Throwable cause)
    {
        super(message, cause);
        this.error = error;
        this.fatal = fatal;
    }

    public int getErrorCode()
    {
        return error;
    }

    public boolean isFatal()
    {
        return fatal;
    }
}

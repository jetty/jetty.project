package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;

public class BlockingCallback implements Callback
{
    private FutureCallback callback = new FutureCallback();

    @Override
    public void succeeded()
    {
        callback.succeeded();
    }

    @Override
    public void failed(Throwable x)
    {
        callback.failed(x);
    }

    public void block() throws IOException
    {
        try
        {
            callback.get();
        }
        catch (InterruptedException e)
        {
            InterruptedIOException exception = new InterruptedIOException();
            exception.initCause(e);
            throw exception;
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            else
                throw new IOException(cause);
        }
    }
}

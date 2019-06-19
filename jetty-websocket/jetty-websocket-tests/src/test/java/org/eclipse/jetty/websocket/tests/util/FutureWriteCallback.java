package org.eclipse.jetty.websocket.tests.util;

import java.util.concurrent.Future;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WriteCallback;

/**
 * Allows events to a {@link WriteCallback} to drive a {@link Future} for the user.
 */
public class FutureWriteCallback extends FutureCallback implements WriteCallback
{
    private static final Logger LOG = Log.getLogger(FutureWriteCallback.class);

    @Override
    public void writeFailed(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".writeFailed",cause);
        failed(cause);
    }

    @Override
    public void writeSuccess()
    {
        if (LOG.isDebugEnabled())
            LOG.debug(".writeSuccess");
        succeeded();
    }
}

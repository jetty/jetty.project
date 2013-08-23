package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.websocket.api.WriteCallback;

public class BlockingWriteCallback extends BlockingCallback implements WriteCallback
{
    @Override
    public void writeFailed(Throwable x)
    {
        failed(x);
    }

    @Override
    public void writeSuccess()
    {
        succeeded();
    }
}

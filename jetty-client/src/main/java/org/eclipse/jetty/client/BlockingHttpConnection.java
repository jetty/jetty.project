package org.eclipse.jetty.client;

import java.io.IOException;

import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

public class BlockingHttpConnection extends AbstractHttpConnection
{

    BlockingHttpConnection(Buffers requestBuffers, Buffers responseBuffers, EndPoint endp)
    {
        super(requestBuffers,responseBuffers,endp);
    }

    @Override
    public Connection handle() throws IOException
    {
        throw new IOException("NOT IMPLEMENTED YET");
    }

}

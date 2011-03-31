package org.eclipse.jetty.nested;

import java.io.IOException;
import org.eclipse.jetty.server.AbstractConnector;

public class NestedConnector extends AbstractConnector
{
    public NestedConnector()
    {
        setAcceptors(0);
    }
    
    public void open() throws IOException
    {
    }

    public void close() throws IOException
    {
    }

    public int getLocalPort()
    {
        return -1;
    }

    public Object getConnection()
    {
        return null;
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        throw new IllegalStateException();
    }

}

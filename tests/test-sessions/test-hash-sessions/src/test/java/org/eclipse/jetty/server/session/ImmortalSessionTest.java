package org.eclipse.jetty.server.session;

public class ImmortalSessionTest extends AbstractImmortalSessionTest
{

    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new HashTestServer(port,max,scavenge);
    }

}

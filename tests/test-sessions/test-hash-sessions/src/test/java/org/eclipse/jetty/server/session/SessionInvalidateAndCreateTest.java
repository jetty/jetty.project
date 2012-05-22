package org.eclipse.jetty.server.session;

import org.junit.Test;

public class SessionInvalidateAndCreateTest extends AbstractSessionInvalidateAndCreateTest
{

    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new HashTestServer(port,max,scavenge);
    }
    
    @Test
    public void testSessionScavenge() throws Exception
    {
        super.testSessionScavenge();
    }
}

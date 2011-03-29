package org.eclipse.jetty.server.session;

import org.junit.Test;

public class RemoveSessionTest extends AbstractRemoveSessionTest
{ 

    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new HashTestServer(port,max,scavenge);
    }
    
    @Test
    public void testRemoveSession() throws Exception
    {
        super.testRemoveSession();
    }

}

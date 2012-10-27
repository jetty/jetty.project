package org.eclipse.jetty.server.session;

import org.junit.Test;

public class SessionRenewTest extends AbstractSessionRenewTest
{

    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new JdbcTestServer(port, max, scavenge);
    }

    @Test
    public void testSessionRenewal() throws Exception
    {
        super.testSessionRenewal();
    }
    
    

}

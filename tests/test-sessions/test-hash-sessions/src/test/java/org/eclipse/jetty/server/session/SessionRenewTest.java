package org.eclipse.jetty.server.session;

import java.io.File;

import org.eclipse.jetty.server.SessionManager;
import org.junit.Test;

public class SessionRenewTest extends AbstractSessionRenewTest
{

    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge)
    {
        return new HashTestServer(port, max, scavenge)
        {

            @Override
            public SessionManager newSessionManager()
            {
                HashSessionManager sessionManager = (HashSessionManager)super.newSessionManager();
                sessionManager.setSavePeriod(2);
                File tmpDir = new File(System.getProperty("java.io.tmpdir"), "hash-session-renew-test");
                tmpDir.deleteOnExit();
                tmpDir.mkdirs();
                sessionManager.setStoreDirectory(tmpDir);
                return sessionManager;
            }
            
        };
    }

    @Test
    public void testSessionRenewal() throws Exception
    {
        super.testSessionRenewal();
    }

    
}

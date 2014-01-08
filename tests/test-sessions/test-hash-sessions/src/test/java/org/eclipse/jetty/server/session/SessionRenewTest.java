//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

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
                try
                {
                    sessionManager.setStoreDirectory(tmpDir);
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
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

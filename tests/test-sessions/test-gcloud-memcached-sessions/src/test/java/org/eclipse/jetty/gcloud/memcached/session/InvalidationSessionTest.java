//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.gcloud.memcached.session;

import org.eclipse.jetty.server.session.AbstractInvalidationSessionTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * InvalidationSessionTest
 *
 *
 */
public class InvalidationSessionTest extends AbstractInvalidationSessionTest
{
    
    @AfterClass
    public static void teardown () throws Exception
    {
        GCloudMemcachedTestSuite.__testSupport.deleteSessions();
    }
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractInvalidationSessionTest#createServer(int)
     */
    @Override
    public AbstractTestServer createServer(int port)
    {
        return new GCloudMemcachedTestServer(port);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractInvalidationSessionTest#pause()
     */
    @Override
    public void pause()
    {
        //This test moves around a session between 2 nodes. After it is invalidated on the 1st node,
        //it will still be in the memory of the 2nd node. We need to wait until after the stale time
        //has expired on node2 for it to reload the session and discover it has been deleted.
        try
        {
            Thread.currentThread().sleep((2*GCloudMemcachedTestServer.STALE_INTERVAL_SEC)*1000);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        
    }

}

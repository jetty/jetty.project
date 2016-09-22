//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.session.AbstractSessionExpiryTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Assert;


/**
 * SessionExpiryTest
 *
 *
 */
public class SessionExpiryTest extends AbstractSessionExpiryTest
{

    @AfterClass
    public static void teardown () throws Exception
    {
       GCloudTestSuite.__testSupport.deleteSessions();
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionExpiryTest#createServer(int, int, int, int)
     */
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
        return  new GCloudTestServer(port, max, scavenge, evictionPolicy);
    }

    @Test
    @Override
    public void testSessionNotExpired() throws Exception
    {
        super.testSessionNotExpired();
        GCloudTestSuite.__testSupport.deleteSessions();
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractSessionExpiryTest#testSessionExpiry()
     */
    @Test
    @Override
    public void testSessionExpiry() throws Exception
    {
        super.testSessionExpiry();

        GCloudTestSuite.__testSupport.deleteSessions();
    }

    @Override
    public void verifySessionCreated(TestHttpSessionListener listener, String sessionId)
    {
        super.verifySessionCreated(listener, sessionId);
        try {GCloudTestSuite.__testSupport.assertSessions(1);}catch(Exception e){ Assert.fail(e.getMessage());}
    }

    @Override
    public void verifySessionDestroyed(TestHttpSessionListener listener, String sessionId)
    {
        super.verifySessionDestroyed(listener, sessionId);
        //try{GCloudTestSuite.__testSupport.assertSessions(0);}catch(Exception e) {Assert.fail(e.getMessage());}
    }

    
    
}

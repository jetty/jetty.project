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


package org.eclipse.jetty.server.session;

import org.junit.After;
import org.junit.Before;

/**
 * IdleSessionTest
 *
 *
 */
public class IdleSessionTest extends AbstractIdleSessionTest
{

    @Before
    public void before() throws Exception
    {
       FileTestServer.setup();
    }
    
    @After 
    public void after()
    {
       FileTestServer.teardown();
    }
    
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#createServer(int, int, int, int)
     */
    @Override
    public AbstractTestServer createServer(final int port, final int max, final int scavenge, final int evictionPolicy) throws Exception
    {
       return new FileTestServer(port,max,scavenge, evictionPolicy);
    }

    

    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#checkSessionIdled(java.lang.String)
     */
    @Override
    public void checkSessionIdled(String sessionId)
    {
        FileTestServer.assertStoreDirEmpty(false);
        FileTestServer.assertFileExists(sessionId, true);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#checkSessionDeIdled(java.lang.String)
     */
    @Override
    public void checkSessionDeIdled(String sessionId)
    {
        //Can't check absence of file to indicate session is de-idled 
        //because the FileSessionDataStore writes out the session to a file if anything changes.
        //The test changes an attribute so the file will probably exist.
    }


    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#deleteSessionData(java.lang.String)
     */
    @Override
    public void deleteSessionData(String sessionId)
    {
        FileTestServer.deleteFile(sessionId);
        FileTestServer.assertFileExists(sessionId, false);
    }



}

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


package org.eclipse.jetty.nosql.mongodb;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.jetty.server.session.AbstractIdleSessionTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.util.thread.Locker.Lock;
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
    public  void beforeTest() throws Exception
    {
        MongoTestServer.dropCollection();
        MongoTestServer.createCollection();
    }

    @After
    public  void afterTest() throws Exception
    {
        MongoTestServer.dropCollection();
    }
    
    

    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#createServer(int, int, int, int)
     */
    @Override
    public AbstractTestServer createServer(final int port, final int max, final int scavenge, final int evictionPolicy) throws Exception
    {
        return  new MongoTestServer(port,max,scavenge, evictionPolicy);
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#checkSessionIdled(java.lang.String)
     */
    @Override
    public void checkSessionIdled(String sessionId)
    {
        assertNotNull(_servlet);
        assertNotNull(_servlet._session);
        try (Lock lock = ((Session)_servlet._session).lock())
        {
            assertTrue(!((Session)_servlet._session).isResident());
        }
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#checkSessionDeIdled(java.lang.String)
     */
    @Override
    public void checkSessionDeIdled(String sessionId)
    {
        assertNotNull(_servlet);
        assertNotNull(_servlet._session);
        try (Lock lock = ((Session)_servlet._session).lock())
        {
            assertTrue(((Session)_servlet._session).isResident());
        }
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractIdleSessionTest#deleteSessionData(java.lang.String)
     */
    @Override
    public void deleteSessionData(String sessionId)
    {
        try
        {
            MongoTestServer.dropCollection();
        }
        catch (Exception e)
        {
            fail(e.getMessage());
        }

    }

}

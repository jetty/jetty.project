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


package org.eclipse.jetty.nosql.mongodb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.UnknownHostException;

import org.eclipse.jetty.server.session.AbstractStopSessionManagerPreserveSessionTest;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Before;
import org.junit.Test;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * StopSessionManagerPreserveSessionTest
 *
 *
 */
public class StopSessionManagerPreserveSessionTest extends AbstractStopSessionManagerPreserveSessionTest
{
    DBCollection _sessions;
    
    @Before
    public void setUp() throws UnknownHostException, MongoException
    {
        _sessions = new Mongo().getDB("HttpSessions").getCollection("sessions");
    }
    
   
    
    public MongoTestServer createServer(int port)
    {
        MongoTestServer server =  new MongoTestServer(port); 
        server.getServer().setStopTimeout(0);
        return server;
    }
    
    

    @Override
    public void checkSessionPersisted(boolean expected)
    {
        DBObject dbSession = _sessions.findOne(new BasicDBObject("id", _id));

        if (expected)
        {
            assertTrue(dbSession != null);
            assertEquals(expected, dbSession.get("valid"));
        }
        else
        {
            assertTrue(dbSession==null);
        }
    }


    @Override
    public void configureSessionManagement(ServletContextHandler context)
    {
        ((MongoSessionManager)context.getSessionHandler().getSessionManager()).setPreserveOnStop(true);
    }

    /**
     * @throws Exception
     */
    @Test
    public void testStopSessionManagerPreserveSession() throws Exception
    {
        super.testStopSessionManagerPreserveSession();
    }



}

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jetty.server.session.AbstractCreateAndInvalidateTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.junit.After;
import org.junit.Before;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;

/**
 * CreateAndInvalidateTest
 *
 *
 */
public class CreateAndInvalidateTest extends AbstractCreateAndInvalidateTest
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
     * @see org.eclipse.jetty.server.session.AbstractCreateAndInvalidateTest#createServer(int, int, int, int)
     */
    @Override
    public AbstractTestServer createServer(final int port, final int max, final int scavenge, final int evictionPolicy) throws Exception
    {
        return  new MongoTestServer(port,max,scavenge, evictionPolicy);
    }
    
  

    /** 
     * @see org.eclipse.jetty.server.session.AbstractCreateAndInvalidateTest#checkSession(java.lang.String, boolean)
     */
    @Override
    public void checkSession(String sessionId, boolean isPersisted) throws Exception
    {
        DBCollection sessions = MongoTestServer.getCollection();           
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,sessionId));
        if (!isPersisted)
            assertNull(o);
        else
            assertNotNull(o);
    }

    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractCreateAndInvalidateTest#checkSessionByKey(java.lang.String, java.lang.String, boolean)
     */
    public void checkSessionByKey (String sessionId, String context, boolean isPersisted) throws Exception
    {
        DBCollection sessions = MongoTestServer.getCollection();           
        DBObject o = sessions.findOne(new BasicDBObject(MongoSessionDataStore.__ID,sessionId));
        if (!isPersisted)
            assertNull(o);
        else
            assertNotNull(o);
        
        if (isPersisted)
        {
            Boolean valid = (Boolean)o.get(MongoSessionDataStore.__VALID);   
            assertNotNull(valid);
            assertTrue(valid);
            DBObject c = (DBObject)o.get("context");
            assertNotNull(c);
            DBObject c1 = (DBObject)c.get(context);
            assertNotNull(c1);
        }
    }
}

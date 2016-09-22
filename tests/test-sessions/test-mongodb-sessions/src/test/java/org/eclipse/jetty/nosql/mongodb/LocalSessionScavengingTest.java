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

import org.eclipse.jetty.server.session.AbstractLocalSessionScavengingTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class LocalSessionScavengingTest extends AbstractLocalSessionScavengingTest
{

    
    @BeforeClass
    public static void beforeClass() throws Exception
    {
        MongoTestServer.dropCollection();
        MongoTestServer.createCollection();
    }

    @AfterClass
    public static void afterClass() throws Exception
    {
        MongoTestServer.dropCollection();
    }
    
    @Override
    public AbstractTestServer createServer(int port, int max, int scavenge, int evictionPolicy) throws Exception
    {
       MongoTestServer mserver=new MongoTestServer(port,max,scavenge, evictionPolicy);
       
       return mserver;
    }

    @Override
    public void testLocalSessionsScavenging() throws Exception
    {
        super.testLocalSessionsScavenging();
    }
    
    

}

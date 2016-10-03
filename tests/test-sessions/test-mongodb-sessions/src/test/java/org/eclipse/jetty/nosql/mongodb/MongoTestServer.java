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

import java.net.UnknownHostException;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.SessionHandler;

import com.mongodb.DBCollection;
import com.mongodb.Mongo;
import com.mongodb.MongoException;


/**
 * @version $Revision$ $Date$
 */
public class MongoTestServer extends AbstractTestServer
{
    static int __workers=0;
    
    
    
    public static void dropCollection () throws MongoException, UnknownHostException
    {
        new Mongo().getDB("HttpSessions").getCollection("testsessions").drop();
    }
    
    
    public static void createCollection() throws UnknownHostException, MongoException
    {
        new Mongo().getDB("HttpSessions").createCollection("testsessions", null);
    }
    
    
    public static DBCollection getCollection () throws UnknownHostException, MongoException 
    {
        return new Mongo().getDB("HttpSessions").getCollection("testsessions");
    }
    
    
    public MongoTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod) throws Exception
    {
        super(port, maxInactivePeriod, scavengePeriod, idlePassivatePeriod);
    }

   
    
    
    public MongoTestServer(int port, int maxInactivePeriod, int scavengePeriod, int idlePassivatePeriod, boolean saveAllAttributes) throws Exception
    {
        super(port, maxInactivePeriod, scavengePeriod, idlePassivatePeriod);
    }



    public SessionHandler newSessionHandler()
    {
        SessionHandler handler = new SessionHandler();
        try
        {
            MongoSessionDataStore ds = new MongoSessionDataStore();
            ds.setDBCollection(getCollection());
            ds.setGracePeriodSec(_scavengePeriod);
            
            DefaultSessionCache ss = new DefaultSessionCache(handler);
            handler.setSessionCache(ss);
            ss.setSessionDataStore(ds);
            return handler;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        
        
    }
    
    public static void main(String... args) throws Exception
    {
/*        MongoTestServer server8080 = new MongoTestServer(8080);
        server8080.addContext("/").addServlet(SessionDump.class,"/");
        server8080.start();
        
        MongoTestServer server8081 = new MongoTestServer(8081);
        server8081.addContext("/").addServlet(SessionDump.class,"/");
        server8081.start();
        
        server8080.join();
        server8081.join();*/
    }

}

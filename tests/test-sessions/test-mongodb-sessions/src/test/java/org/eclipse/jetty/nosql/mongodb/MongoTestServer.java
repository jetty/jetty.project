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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.SessionHandler;

import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoException;


/**
 * @version $Revision$ $Date$
 */
public class MongoTestServer extends AbstractTestServer
{
    static int __workers=0;
    private boolean _saveAllAttributes = false; // false save dirty, true save all
    
    
    public static class TestMongoSessionIdManager extends MongoSessionIdManager 
    {

        public TestMongoSessionIdManager(Server server) throws UnknownHostException, MongoException
        {
            super(server);
        }
        
        
        public void deleteAll ()
        {
            
            DBCursor checkSessions = _sessions.find();

            for (DBObject session : checkSessions)
            {
                _sessions.remove(session);
            }
        }
        
        public void cancelScavenge ()
        {
            if (_scavengerTask != null)
                _scavengerTask.cancel();
        }
    }
    
    public MongoTestServer(int port)
    {
        super(port, 30, 10);
    }

    public MongoTestServer(int port, int maxInactivePeriod, int scavengePeriod)
    {
        super(port, maxInactivePeriod, scavengePeriod);
    }
    
    
    public MongoTestServer(int port, int maxInactivePeriod, int scavengePeriod, boolean saveAllAttributes)
    {
        super(port, maxInactivePeriod, scavengePeriod);
        
        _saveAllAttributes = saveAllAttributes;
    }

    public SessionIdManager newSessionIdManager(Object config)
    {
        try
        {
            MongoSessionIdManager idManager = new TestMongoSessionIdManager(_server);
            idManager.setWorkerName("w"+(__workers++));
            idManager.setScavengePeriod(_scavengePeriod);                  

            return idManager;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public SessionManager newSessionManager()
    {
        MongoSessionManager manager;
        try
        {
            manager = new MongoSessionManager();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        
        manager.setSavePeriod(0);
        manager.setStalePeriod(0);
        manager.setSaveAllAttributes(_saveAllAttributes);
        //manager.setScavengePeriod((int)TimeUnit.SECONDS.toMillis(_scavengePeriod));
        return manager;
    }

    public SessionHandler newSessionHandler(SessionManager sessionManager)
    {
        return new SessionHandler(sessionManager);
    }
    
    public static void main(String... args) throws Exception
    {
        MongoTestServer server8080 = new MongoTestServer(8080);
        server8080.addContext("/").addServlet(SessionDump.class,"/");
        server8080.start();
        
        MongoTestServer server8081 = new MongoTestServer(8081);
        server8081.addContext("/").addServlet(SessionDump.class,"/");
        server8081.start();
        
        server8080.join();
        server8081.join();
    }

}

//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server.session;

import java.io.File;
import java.net.InetSocketAddress;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.memcached.session.MemcachedSessionDataMapFactory;
import org.eclipse.jetty.nosql.mongodb.MongoSessionDataStoreFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.session.CachingSessionDataStoreFactory;
import org.eclipse.jetty.session.DatabaseAdaptor;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.session.HouseKeeper;
import org.eclipse.jetty.session.NullSessionCache;
import org.eclipse.jetty.session.NullSessionCacheFactory;
import org.eclipse.jetty.session.NullSessionDataStore;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionHandler;

@SuppressWarnings("unused")
public class SessionDocs
{
    public void minimumDefaultSessionIdManager()
    {
        //tag::default[]
        Server server = new Server();
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
        //you must set the workerName unless you set the env viable JETTY_WORKER_NAME
        idMgr.setWorkerName("server3");
        server.setSessionIdManager(idMgr);
        //end::default[]
    }

    public void defaultSessionIdManagerWithHouseKeeper()
    {
        try
        {
            //tag::housekeeper[]
            Server server = new Server();
            DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
            idMgr.setWorkerName("server7");
            server.setSessionIdManager(idMgr);

            HouseKeeper houseKeeper = new HouseKeeper();
            houseKeeper.setSessionIdManager(idMgr);
            //set the frequency of scavenge cycles
            houseKeeper.setIntervalSec(600L);
            idMgr.setSessionHouseKeeper(houseKeeper);
            //end::housekeeper[]
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    public void servletContextWithSessionHandler()
    {
        //tag:schsession[]
        Server server = new Server();
        
        ServletContextHandler context = new ServletContextHandler(server, "/foo", ServletContextHandler.SESSIONS);
        SessionHandler sessions = context.getSessionHandler();
        //make idle sessions valid for only 5mins
        sessions.setMaxInactiveInterval(300);
        //turn off use of cookies
        sessions.setUsingCookies(false);
        
        server.setHandler(context);
        //end::schsession[]
    }
    
    public void webAppWithSessionHandler()
    {
        //tag:wacsession[]
        Server server = new Server();
        
        WebAppContext context = new WebAppContext();
        SessionHandler sessions = context.getSessionHandler();
        //make idle sessions valid for only 5mins
        sessions.setMaxInactiveInterval(300);
        //turn off use of cookies
        sessions.setUsingCookies(false);
        
        server.setHandler(context);
        //end::wacsession[]
    }
    
    public void defaultSessionCache()
    {
        //tag::defaultsessioncache[]
        Server server = new Server();
       
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        //EVICT_ON_INACTIVE: evict a session after 60sec inactivity
        cacheFactory.setEvictionPolicy(60);
        //Only useful with the EVICT_ON_INACTIVE policy
        cacheFactory.setSaveOnInactiveEviction(true);
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setInvalidateOnShutdown(false);
        cacheFactory.setRemoveUnloadableSessions(true);
        cacheFactory.setSaveOnCreate(true);

        //Add the factory as a bean to the server, now whenever a 
        //SessionHandler starts it will consult the bean to create a new DefaultSessionCache
        server.addBean(cacheFactory);
        //end::defaultsessioncache[]
    }
    
    public void nullSessionCache()
    {
        //tag::nullsessioncache[]
        Server server = new Server();
        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setRemoveUnloadableSessions(true);
        cacheFactory.setSaveOnCreate(true);

        //Add the factory as a bean to the server, now whenever a 
        //SessionHandler starts it will consult the bean to create a new NullSessionCache
        server.addBean(cacheFactory);
        //end::nullsessioncache[]
    }

    public void mixedSessionCache()
    {
        //tag::mixedsessioncache[]
        Server server = new Server();

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        //NEVER_EVICT
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setInvalidateOnShutdown(false);
        cacheFactory.setRemoveUnloadableSessions(true);
        cacheFactory.setSaveOnCreate(true);

        //Add the factory as a bean to the server, now whenever a 
        //SessionHandler starts it will consult the bean to create a new DefaultSessionCache
        server.addBean(cacheFactory);
        
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);
        
        //Add a webapp that will use a DefaultSessionCache via the DefaultSessionCacheFactory
        WebAppContext app1 = new WebAppContext();
        app1.setContextPath("/app1");
        contexts.addHandler(app1);
        
        //Add a webapp that uses an explicit NullSessionCache instead
        WebAppContext app2 = new WebAppContext();
        app2.setContextPath("/app2");
        NullSessionCache nullSessionCache = new NullSessionCache(app2.getSessionHandler());
        nullSessionCache.setFlushOnResponseCommit(true);
        nullSessionCache.setRemoveUnloadableSessions(true);
        nullSessionCache.setSaveOnCreate(true);
        //If we pass an existing SessionCache instance to the SessionHandler, it must be
        //fully configured: this means we must also provide SessionDataStore
        nullSessionCache.setSessionDataStore(new NullSessionDataStore());
        app2.getSessionHandler().setSessionCache(nullSessionCache);
        //end::mixedsessioncache[]
    }
    
    public void fileSessionDataStoreFactory()
    {
      //tag::filesessiondatastorefactory[]
        Server server = new Server();

        //First lets configure a DefaultSessionCacheFactory
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        //NEVER_EVICT
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setInvalidateOnShutdown(false);
        cacheFactory.setRemoveUnloadableSessions(true);
        cacheFactory.setSaveOnCreate(true);

        //Add the factory as a bean to the server, now whenever a 
        //SessionHandler starts it will consult the bean to create a new DefaultSessionCache
        server.addBean(cacheFactory);
        
        //Now, lets configure a FileSessionDataStoreFactory
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(new File("/tmp/sessions"));
        storeFactory.setGracePeriodSec(3600);
        storeFactory.setSavePeriodSec(0);
        
        //Add the factory as a bean on the server, now whenever a
        //SessionHandler starts, it will consult the bean to create a new FileSessionDataStore
        //for use by the DefaultSessionCache
        server.addBean(storeFactory);
      //end::filesessiondatastorefactory[]  
    }
    
    public void fileSessionDataStore()
    {
      //tag::filesessiondatastore[]

        //create a context
        WebAppContext app1 = new WebAppContext();
        app1.setContextPath("/app1");
        
        //First, we create a DefaultSessionCache
        DefaultSessionCache cache = new DefaultSessionCache(app1.getSessionHandler());
        cache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        cache.setFlushOnResponseCommit(true);
        cache.setInvalidateOnShutdown(false);
        cache.setRemoveUnloadableSessions(true);
        cache.setSaveOnCreate(true);
        
        //Now, we configure a FileSessionDataStore
        FileSessionDataStore store = new FileSessionDataStore();
        store.setStoreDir(new File("/tmp/sessions"));
        store.setGracePeriodSec(3600);
        store.setSavePeriodSec(0);
        
        //Tell the cache to use the store
        cache.setSessionDataStore(store);
        
        //Tell the contex to use the cache/store combination
        app1.getSessionHandler().setSessionCache(cache);
        
      //end::filesessiondatastore[]  
    }
    
    public void cachingSessionDataStore()
    {
        //tag::cachingsds[]
        Server server = new Server();
        
        //Make a factory for memcached L2 caches for SessionData
        MemcachedSessionDataMapFactory mapFactory = new MemcachedSessionDataMapFactory();
        mapFactory.setExpirySec(0); //items in memcached don't expire
        mapFactory.setHeartbeats(true); //tell memcached to use heartbeats
        mapFactory.setAddresses(new InetSocketAddress("localhost", 11211)); //use a local memcached instance
        mapFactory.setWeights(new int[] {100}); //set the weighting
        
        
        //Make a FileSessionDataStoreFactory for creating FileSessionDataStores
        //to persist the session data
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(new File("/tmp/sessions"));
        storeFactory.setGracePeriodSec(3600);
        storeFactory.setSavePeriodSec(0);
        
        //Make a factory that plugs the L2 cache into the SessionDataStore
        CachingSessionDataStoreFactory cachingSessionDataStoreFactory = new CachingSessionDataStoreFactory();
        cachingSessionDataStoreFactory.setSessionDataMapFactory(mapFactory);
        cachingSessionDataStoreFactory.setSessionStoreFactory(storeFactory);
        
        //Register it as a bean so that all SessionHandlers will use it
        //to make FileSessionDataStores that use memcached as an L2 SessionData cache.
        server.addBean(cachingSessionDataStoreFactory);
        //end::cachingsds[]
    }

    public void jdbcSessionDataStore()
    {
        //tag::dbaDatasource[]
        DatabaseAdaptor datasourceAdaptor = new DatabaseAdaptor();
        datasourceAdaptor.setDatasourceName("/jdbc/myDS");
        //end::dbaDatasource[]
        
        //tag::dbaDriver[]
        DatabaseAdaptor driverAdaptor = new DatabaseAdaptor();
        driverAdaptor.setDriverInfo("com.mysql.jdbc.Driver", "jdbc:mysql://127.0.0.1:3306/sessions?user=sessionsadmin");
        //end::dbaDriver[]
    }
    
    public void mongoSessionDataStore()
    {
        //tag::mongosdfactory[]
        Server server = new Server();
        
        MongoSessionDataStoreFactory mongoSessionDataStoreFactory = new MongoSessionDataStoreFactory();
        mongoSessionDataStoreFactory.setGracePeriodSec(3600);
        mongoSessionDataStoreFactory.setSavePeriodSec(0);
        mongoSessionDataStoreFactory.setDbName("HttpSessions");
        mongoSessionDataStoreFactory.setCollectionName("JettySessions");
        
        // Either set the connectionString
        mongoSessionDataStoreFactory.setConnectionString("mongodb:://localhost:27017");
        // or alternatively set the host and port.
        mongoSessionDataStoreFactory.setHost("localhost");
        mongoSessionDataStoreFactory.setPort(27017);
        //end::mongosdfactory[]
    }
}

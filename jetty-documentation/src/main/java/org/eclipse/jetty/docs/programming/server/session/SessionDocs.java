//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server.session;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.FileSessionDataStore;
import org.eclipse.jetty.server.session.FileSessionDataStoreFactory;
import org.eclipse.jetty.server.session.HouseKeeper;
import org.eclipse.jetty.server.session.NullSessionCache;
import org.eclipse.jetty.server.session.NullSessionCacheFactory;
import org.eclipse.jetty.server.session.NullSessionDataStore;
import org.eclipse.jetty.server.session.SessionCache;
import org.eclipse.jetty.webapp.WebAppContext;

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
    
    public void defaultSessionCache()
    {
        //tag::defaultsessioncache[]
        Server server = new Server();
       
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        //EVICT_ON_INACTIVE: evict a session after 60sec inactivity
        cacheFactory.setEvictionPolicy(60);
        //Only useful with the EVICT_ON_INACTIVE policy
        cacheFactory.setSaveOnInactiveEvict(true);
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
}

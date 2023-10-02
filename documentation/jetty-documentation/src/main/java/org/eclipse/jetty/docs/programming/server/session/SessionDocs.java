//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.util.Properties;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.gcloud.session.GCloudSessionDataStoreFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.memcached.session.MemcachedSessionDataMapFactory;
import org.eclipse.jetty.nosql.mongodb.MongoSessionDataStoreFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
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
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.util.Callback;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.infinispan.manager.DefaultCacheManager;

@SuppressWarnings("unused")
public class SessionDocs
{
    public void cachingSessionDataStore()
    {
        //tag::cachingsds[]
        Server server = new Server();

        //Make a factory for memcached L2 caches for SessionData
        MemcachedSessionDataMapFactory mapFactory = new MemcachedSessionDataMapFactory();
        mapFactory.setExpirySec(0); //items in memcached don't expire
        mapFactory.setHeartbeats(true); //tell memcached to use heartbeats
        mapFactory.setAddresses(new InetSocketAddress("localhost", 11211)); //use a local memcached instance
        mapFactory.setWeights(new int[]{100}); //set the weighting

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

        //Register it as a bean so that all SessionManagers will use it
        //to make FileSessionDataStores that use memcached as an L2 SessionData cache.
        server.addBean(cachingSessionDataStoreFactory);
        //end::cachingsds[]
    }

    public void coreSessionHandler()
    {
        try
        {
            //tag:coresession[]
            Server server = new Server();
            org.eclipse.jetty.session.SessionHandler sessionHandler = new org.eclipse.jetty.session.SessionHandler();
            sessionHandler.setSessionCookie("SIMPLE");
            sessionHandler.setUsingCookies(true);
            sessionHandler.setUsingURLs(false);
            sessionHandler.setSessionPath("/");
            server.setHandler(sessionHandler);
            sessionHandler.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    Session session = request.getSession(false);
                    Content.Sink.write(response, true, "Session=" + session.getId(), callback);
                    return true;
                }
            });
            //end::coresession[]
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
        cacheFactory.setSaveOnInactiveEviction(true);
        cacheFactory.setFlushOnResponseCommit(true);
        cacheFactory.setInvalidateOnShutdown(false);
        cacheFactory.setRemoveUnloadableSessions(true);
        cacheFactory.setSaveOnCreate(true);

        //Add the factory as a bean to the server, now whenever a
        //SessionManager starts it will consult the bean to create a new DefaultSessionCache
        server.addBean(cacheFactory);
        //end::defaultsessioncache[]
    }

    public void defaultSessionIdManagerWithHouseKeeper()
    {
        try
        {
            //tag::housekeeper[]
            Server server = new Server();
            DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
            idMgr.setWorkerName("server7");
            server.addBean(idMgr, true);

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

        //Tell the context to use the cache/store combination
        app1.getSessionHandler().setSessionCache(cache);

        //end::filesessiondatastore[]
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
        //SessionManager starts it will consult the bean to create a new DefaultSessionCache
        server.addBean(cacheFactory);

        //Now, lets configure a FileSessionDataStoreFactory
        FileSessionDataStoreFactory storeFactory = new FileSessionDataStoreFactory();
        storeFactory.setStoreDir(new File("/tmp/sessions"));
        storeFactory.setGracePeriodSec(3600);
        storeFactory.setSavePeriodSec(0);

        //Add the factory as a bean on the server, now whenever a
        //SessionManager starts, it will consult the bean to create a new FileSessionDataStore
        //for use by the DefaultSessionCache
        server.addBean(storeFactory);
        //end::filesessiondatastorefactory[]
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

    public void minimumDefaultSessionIdManager()
    {
        //tag::default[]
        Server server = new Server();
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
        //you must set the workerName unless you set the env viable JETTY_WORKER_NAME
        idMgr.setWorkerName("server3");
        server.addBean(idMgr, true);
        //end::default[]
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
        //SessionManager starts it will consult the bean to create a new DefaultSessionCache
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

    public void infinispanEmbedded()
    {
        try
        {
            //tag::infinispanembed[]
            /* Create a core SessionHandler
             * Alternatively in a Servlet Environment do:
             * WebAppContext webapp = new WebAppContext();
             * SessionHandler sessionHandler = webapp.getSessionHandler();
             */
            SessionHandler sessionHandler = new SessionHandler();

            //Use an Infinispan local cache configured via an infinispan xml file
            DefaultCacheManager defaultCacheManager = new DefaultCacheManager("path/to/infinispan.xml");
            Cache<String, InfinispanSessionData> localCache = defaultCacheManager.getCache();

            //Configure the Jetty session datastore with Infinispan
            InfinispanSessionDataStore infinispanSessionDataStore = new InfinispanSessionDataStore();
            infinispanSessionDataStore.setCache(localCache);
            infinispanSessionDataStore.setSerialization(false); //local cache does not serialize session data
            infinispanSessionDataStore.setInfinispanIdleTimeoutSec(0); //do not use infinispan auto delete of unused sessions
            infinispanSessionDataStore.setQueryManager(new org.eclipse.jetty.session.infinispan.EmbeddedQueryManager(localCache)); //enable Jetty session scavenging
            infinispanSessionDataStore.setGracePeriodSec(3600);
            infinispanSessionDataStore.setSavePeriodSec(0);

            //Configure a SessionHandler to use the local Infinispan cache as a store of SessionData
            DefaultSessionCache sessionCache = new DefaultSessionCache(sessionHandler);
            sessionCache.setSessionDataStore(infinispanSessionDataStore);
            sessionHandler.setSessionCache(sessionCache);

            //end::infinispanembed[]
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void infinispanRemote()
    {
        try
        {
            //tag::infinispanremote[]
            /* Create a core SessionHandler
             * Alternatively in a Servlet Environment do:
             * WebAppContext webapp = new WebAppContext();
             * SessionHandler sessionHandler = webapp.getSessionHandler();
             */
            SessionHandler sessionHandler = new SessionHandler();

            //Configure Infinispan to provide a remote cache called "JettySessions"
            Properties hotrodProperties = new Properties();
            hotrodProperties.load(new FileInputStream("/path/to/hotrod-client.properties"));
            org.infinispan.client.hotrod.configuration.ConfigurationBuilder configurationBuilder = new ConfigurationBuilder();
            configurationBuilder.withProperties(hotrodProperties);
            configurationBuilder.marshaller(new ProtoStreamMarshaller());
            configurationBuilder.addContextInitializer(new org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer());
            org.infinispan.client.hotrod.RemoteCacheManager remoteCacheManager = new RemoteCacheManager(configurationBuilder.build());
            RemoteCache<String, InfinispanSessionData> remoteCache = remoteCacheManager.getCache("JettySessions");

            //Configure the Jetty session datastore with Infinispan
            InfinispanSessionDataStore infinispanSessionDataStore = new InfinispanSessionDataStore();
            infinispanSessionDataStore.setCache(remoteCache);
            infinispanSessionDataStore.setSerialization(true); //remote cache serializes session data
            infinispanSessionDataStore.setInfinispanIdleTimeoutSec(0); //do not use infinispan auto delete of unused sessions
            infinispanSessionDataStore.setQueryManager(new org.eclipse.jetty.session.infinispan.RemoteQueryManager(remoteCache)); //enable Jetty session scavenging
            infinispanSessionDataStore.setGracePeriodSec(3600);
            infinispanSessionDataStore.setSavePeriodSec(0);

            //Configure a SessionHandler to use a remote Infinispan cache as a store of SessionData
            DefaultSessionCache sessionCache = new DefaultSessionCache(sessionHandler);
            sessionCache.setSessionDataStore(infinispanSessionDataStore);
            sessionHandler.setSessionCache(sessionCache);
            //end::infinispanremote[]
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void gcloudSessionDataStore()
    {
        try
        {
            //tag::gcloudsessiondatastorefactory[]
            Server server = new Server();

            //Ensure there is a SessionCacheFactory
            DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();

            //Add the factory as a bean to the server, now whenever a
            //SessionManager starts it will consult the bean to create a new DefaultSessionCache
            server.addBean(cacheFactory);

            //Configure the GCloudSessionDataStoreFactory
            GCloudSessionDataStoreFactory storeFactory = new GCloudSessionDataStoreFactory();
            storeFactory.setGracePeriodSec(3600);
            storeFactory.setSavePeriodSec(0);
            storeFactory.setBackoffMs(2000); //increase the time between retries of failed writes
            storeFactory.setMaxRetries(10); //increase the number of retries of failed writes

            //Add the factory as a bean on the server, now whenever a
            //SessionManager starts, it will consult the bean to create a new GCloudSessionDataStore
            //for use by the DefaultSessionCache
            server.addBean(storeFactory);
            //end::gcloudsessiondatastorefactory[]
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
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
        //SessionManager starts it will consult the bean to create a new NullSessionCache
        server.addBean(cacheFactory);
        //end::nullsessioncache[]
    }

    public void servletContextWithSessionHandler()
    {
        //tag:schsession[]
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler("/foo", ServletContextHandler.SESSIONS);
        //make idle sessions valid for only 5mins
        context.getSessionHandler().setMaxInactiveInterval(300);
        //turn off use of cookies
        context.getSessionHandler().setUsingCookies(false);

        server.setHandler(context);
        //end::schsession[]
    }

    public void webAppWithSessionHandler()
    {
        //tag:wacsession[]
        Server server = new Server();

        WebAppContext context = new WebAppContext();
        //make idle sessions valid for only 5mins
        context.getSessionHandler().setMaxInactiveInterval(300);
        //turn off use of cookies
        context.getSessionHandler().setUsingCookies(false);

        server.setHandler(context);
        //end::wacsession[]
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.server.session.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.Properties;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.RemoteQueryManager;
import org.eclipse.jetty.session.infinispan.SessionDataMarshaller;
import org.eclipse.jetty.toolchain.test.IO;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;

import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * RemoteInfinispanSessionDataStoreTest
 *
 *
 */
public class RemoteInfinispanSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    public static RemoteInfinispanTestSupport __testSupport;

   
    
    
    @Before
    public void setup () throws Exception
    {
      __testSupport = new RemoteInfinispanTestSupport("remote-session-test");
      __testSupport.setup();
    }
    
    @After
    public void teardown () throws Exception
    {
       __testSupport.teardown();
    }
    


    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(__testSupport.getCache());
        factory.setQueryManager(new RemoteQueryManager(__testSupport.getCache()));
        return factory;
    }

    
    @Override
    public void persistSession(SessionData data) throws Exception
    {
        __testSupport.createSession(data);

    }

   
    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        //Not used by testLoadSessionFails() 
    }

   
    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return __testSupport.checkSessionExists(data);
    }
 

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        return __testSupport.checkSessionPersisted(data);
    }
    
    

    /** 
     * This test currently won't work for Infinispan - there is currently no
     * means to query it to find sessions that have expired.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStoreTest#testGetExpiredPersistedAndExpiredOnly()
     */
    @Override
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {
        
    }
    
    

    /** 
     * This test won't work for Infinispan - there is currently no
     * means to query infinispan to find other expired sessions.
     */
    @Override
    public void testGetExpiredDifferentNode() throws Exception
    {
        super.testGetExpiredDifferentNode();
    }
    
    
    /** 
     * This test deliberately sets the infinispan cache to null to
     * try and provoke an exception in the InfinispanSessionDataStore.load() method.
     */
    @Override
    public void testLoadSessionFails() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);


        //persist a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now-1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        store.start();
        
        ((InfinispanSessionDataStore)store).setCache(null);
        

        //test that loading it fails
        try
        {
            store.load("222");
            fail("Session should be unreadable");
        }
        catch (UnreadableSessionDataException e)
        {
            //expected exception
        }
    }

    @Test
    public void testQuery() throws Exception
    {
        
        SearchMapping mapping = new SearchMapping();
        mapping.entity(SessionData.class).indexed().providedId()
            .property("expiry", ElementType.METHOD).field();
        
        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);

        
        ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
        clientBuilder.withProperties(properties).addServer().host("127.0.0.1").marshaller(new ProtoStreamMarshaller());
            
        RemoteCacheManager remoteCacheManager = new RemoteCacheManager(clientBuilder.build());
        SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(remoteCacheManager);
        FileDescriptorSource fds = new FileDescriptorSource();
        fds.addProtoFiles("/session.proto");
        serCtx.registerProtoFiles(fds);
        serCtx.registerMarshaller(new SessionDataMarshaller());
        
        //RemoteCache<String, SessionData> cache = __testSupport.getCache();
        RemoteCache<String, SessionData> cache = remoteCacheManager.getCache();
        
        ByteArrayOutputStream baos;
        try(InputStream is = RemoteInfinispanSessionDataStoreTest.class.getClassLoader().getResourceAsStream("session.proto"))
        {
            if (is == null)
                throw new IllegalStateException("inputstream is null");
            
            baos = new ByteArrayOutputStream();
            IO.copy(is, baos);
            is.close();
        }
        
        String content = baos.toString("UTF-8");
        remoteCacheManager.getCache("___protobuf_metadata").put("session.proto", content);
        
        SessionData sd1 = new SessionData("sd1", "", "", 0, 0, 0, 1000);
        sd1.setLastNode("fred1");
        cache.put("session1", sd1);
        
        SessionData sd2 = new SessionData("sd2", "", "", 0, 0, 0, 2000);
        sd2.setLastNode("fred2");
        cache.put("session2", sd2);
        
        SessionData sd3 = new SessionData("sd3", "", "", 0, 0, 0, 3000);
        sd3.setLastNode("fred3");
        cache.put("session3", sd3);
                
        QueryFactory qf = Search.getQueryFactory(cache);
        
        
        for(int i=0; i<=3; i++)
        {
            long now = System.currentTimeMillis();
            Query q = qf.from(SessionData.class).having("expiry").lt(now).build();
            assertEquals(i, q.list().size());
            Thread.sleep(1000);
        } 
    }
}



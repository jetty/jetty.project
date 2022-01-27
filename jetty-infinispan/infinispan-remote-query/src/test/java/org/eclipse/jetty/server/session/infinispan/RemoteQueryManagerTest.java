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

package org.eclipse.jetty.server.session.infinispan;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.session.infinispan.RemoteQueryManager;
import org.eclipse.jetty.util.IO;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.configuration.XMLStringConfiguration;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
public class RemoteQueryManagerTest
{
    public static final String DEFAULT_CACHE_NAME = "remote-session-test";

    private static final Logger LOG = LoggerFactory.getLogger(RemoteQueryManagerTest.class);

    private static final Logger INFINISPAN_LOG =
        LoggerFactory.getLogger("org.eclipse.jetty.server.session.infinispan.infinispanLogs");

    private static final Random r = new Random();
    private static final int NUM_SESSIONS = 10;
    private static final int MAX_EXPIRY_TIME = 1000;
    private static final String NODE_ID = "w0";
    private static int count;
    private String host;
    private int port;
    
    GenericContainer infinispan =
        new GenericContainer(System.getProperty("infinispan.docker.image.name", "infinispan/server") +
            ":" + System.getProperty("infinispan.docker.image.version", "11.0.9.Final"))
            .withEnv("USER", "theuser")
            .withEnv("PASS", "foobar")
            .withEnv("MGMT_USER", "admin")
            .withEnv("MGMT_PASS", "admin")
            .withEnv("CONFIG_PATH", "/user-config/config.yaml")
            .waitingFor(new LogMessageWaitStrategy()
                .withRegEx(".*Infinispan Server.*started in.*\\s"))
            .withExposedPorts(4712, 4713, 8088, 8089, 8443, 9990, 9993, 11211, 11222, 11223, 11224)
            .withLogConsumer(new Slf4jLogConsumer(INFINISPAN_LOG))
            .withClasspathResourceMapping("/config.yaml", "/user-config/config.yaml", BindMode.READ_ONLY);

    @BeforeEach
    public void setup() throws Exception
    {
        long start = System.currentTimeMillis();
        infinispan.start();
        host = infinispan.getContainerIpAddress();
        port = infinispan.getMappedPort(11222);
        LOG.info("Infinispan container started for {}:{} - {}ms", host, port,
                 System.currentTimeMillis() - start);
    }

    @AfterEach
    public void stop() throws Exception
    {
        infinispan.stop();
    }

    @Test
    public void testQuery() throws Exception
    {
        SearchMapping mapping = new SearchMapping();
        mapping.entity(InfinispanSessionData.class).indexed().providedId()
            .property("expiry", ElementType.METHOD).field();

        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);

        ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
        clientBuilder.withProperties(properties)
        .addServer()
        .host(this.host).port(this.port)
        .clientIntelligence(ClientIntelligence.BASIC)
        .marshaller(new ProtoStreamMarshaller())
        .security()
        .authentication()
        .username("theuser").password("foobar");

        clientBuilder.addContextInitializer(new InfinispanSerializationContextInitializer());
        
        RemoteCacheManager remoteCacheManager = new RemoteCacheManager(clientBuilder.build());

        //upload the session.proto serialization descriptor to the remote cache
        try (InputStream is = RemoteQueryManagerTest.class.getClassLoader().getResourceAsStream("session.proto");
             ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            if (is == null)
                throw new IllegalStateException("inputstream is null");
            IO.copy(is, baos);
            String content = baos.toString("UTF-8");
            remoteCacheManager.administration().getOrCreateCache("___protobuf_metadata", (String)null).put("session.proto", content);
        }
        
        //make the remote cache encoded with protostream
        String xml = String.format("<infinispan>"  + 
            "<cache-container>" + "<distributed-cache name=\"%s\" mode=\"SYNC\">" +
            "<encoding media-type=\"application/x-protostream\"/>" +
            "</distributed-cache>" +
            "</cache-container>" +
            "</infinispan>", DEFAULT_CACHE_NAME);
        XMLStringConfiguration xmlConfig = new XMLStringConfiguration(xml);
        RemoteCache<String, InfinispanSessionData> cache = remoteCacheManager.administration().getOrCreateCache(DEFAULT_CACHE_NAME, xmlConfig);
        
        //put some sessions into the cache for "foo" context
        ContextHandler fooHandler = new ContextHandler();
        fooHandler.setContextPath("/foo");
        SessionContext fooSessionContext = new SessionContext(NODE_ID, fooHandler.getServletContext());
        Set<SessionData> fooSessions = createSessions(cache, fooSessionContext);
 
        //put some sessions into the cache for "bar" context
        ContextHandler barHandler = new ContextHandler();
        barHandler.setContextPath("/bar");
        SessionContext barSessionContext = new SessionContext(NODE_ID, barHandler.getServletContext());
        Set<SessionData> barSessions = createSessions(cache, barSessionContext);
        
        int time = 500;
        
        //run the query for "foo" context
        checkResults(cache, fooSessionContext, time, fooSessions);
        
        //run the query for the "bar" context
        checkResults(cache, barSessionContext, time, barSessions);
    }
    
    private Set<SessionData> createSessions(RemoteCache<String, InfinispanSessionData> cache, SessionContext sessionContext) throws Exception
    {
        Set<SessionData> sessions = new HashSet<>();
        
        for (int i = 0; i < NUM_SESSIONS; i++)
        {
            //create new sessiondata with random expiry time
            long expiryTime = r.nextInt(MAX_EXPIRY_TIME);
            String id = "sd" + count;
            count++;
            InfinispanSessionData sd = new InfinispanSessionData(id, sessionContext.getCanonicalContextPath(), sessionContext.getVhost(), 0, 0, 0, 0);
            sd.setLastNode(sessionContext.getWorkerName());
            sd.setExpiry(expiryTime);
            sd.serializeAttributes();
            sessions.add(sd);
            //add to cache
            cache.put(id, sd);
        }
        return sessions;
    }
    
    private void checkResults(RemoteCache<String, InfinispanSessionData> cache, SessionContext sessionContext, int time, Set<SessionData> sessions)
    {
        QueryManager qm = new RemoteQueryManager(cache);
        Set<String> queryResult = qm.queryExpiredSessions(sessionContext, time);

        for (SessionData s : sessions)
        {
            if (s.getExpiry() > 0 && s.getExpiry() <= time)
            {
                assertTrue(queryResult.remove(s.getId()));
            }
        }
        assertTrue(queryResult.isEmpty()); //check we got them all
    }
}

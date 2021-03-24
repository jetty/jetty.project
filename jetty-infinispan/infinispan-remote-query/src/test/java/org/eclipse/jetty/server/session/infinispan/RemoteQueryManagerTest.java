//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.session.infinispan;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.session.infinispan.RemoteQueryManager;
import org.eclipse.jetty.session.infinispan.SessionDataMarshaller;
import org.eclipse.jetty.util.IO;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
public class RemoteQueryManagerTest
{
    public static final String DEFAULT_CACHE_NAME = "remote-session-test";

    private static final Logger LOG = LoggerFactory.getLogger(RemoteQueryManagerTest.class);

    private static final Logger INFINISPAN_LOG =
        LoggerFactory.getLogger("org.eclipse.jetty.server.session.infinispan.infinispanLogs");

    private String host;
    private int port;

    GenericContainer infinispan =
        new GenericContainer(System.getProperty("infinispan.docker.image.name", "jboss/infinispan-server") +
            ":" + System.getProperty("infinispan.docker.image.version", "9.4.8.Final"))
            .withEnv("APP_USER", "theuser")
            .withEnv("APP_PASS", "foobar")
            .withEnv("MGMT_USER", "admin")
            .withEnv("MGMT_PASS", "admin")
            .waitingFor(new LogMessageWaitStrategy()
                .withRegEx(".*Infinispan Server.*started in.*\\s"))
            .withExposedPorts(4712, 4713, 8088, 8089, 8443, 9990, 9993, 11211, 11222, 11223, 11224)
            .withLogConsumer(new Slf4jLogConsumer(INFINISPAN_LOG));

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
        mapping.entity(SessionData.class).indexed().providedId().property("expiry", ElementType.FIELD).field();

        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);

        ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
        clientBuilder.withProperties(properties).addServer()
            .host(this.host).port(this.port)
            .clientIntelligence(ClientIntelligence.BASIC)
            .marshaller(new ProtoStreamMarshaller());

        RemoteCacheManager remoteCacheManager = new RemoteCacheManager(clientBuilder.build());
        remoteCacheManager.administration().getOrCreateCache("remote-session-test", (String)null);

        FileDescriptorSource fds = new FileDescriptorSource();
        fds.addProtoFiles("/session.proto");

        SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(remoteCacheManager);
        serCtx.registerProtoFiles(fds);
        serCtx.registerMarshaller(new SessionDataMarshaller());

        try (InputStream is = RemoteQueryManagerTest.class.getClassLoader().getResourceAsStream("session.proto");
             ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            if (is == null)
                throw new IllegalStateException("inputstream is null");
            IO.copy(is, baos);
            String content = baos.toString("UTF-8");
            remoteCacheManager.getCache("___protobuf_metadata").put("session.proto", content);
        }

        RemoteCache<String, SessionData> cache = remoteCacheManager.getCache(DEFAULT_CACHE_NAME);

        //put some sessions into the remote cache
        int numSessions = 10;
        long currentTime = 500;
        int maxExpiryTime = 1000;
        Set<String> expiredSessions = new HashSet<>();
        Random r = new Random();

        for (int i = 0; i < numSessions; i++)
        {
            String id = "sd" + i;
            //create new sessiondata with random expiry time
            long expiryTime = r.nextInt(maxExpiryTime);
            InfinispanSessionData sd = new InfinispanSessionData(id, "", "", 0, 0, 0, 0);
            sd.setLastNode("lastNode");
            sd.setExpiry(expiryTime);

            //if this entry has expired add it to expiry list
            if (expiryTime <= currentTime)
                expiredSessions.add(id);

            //add to cache
            cache.put(id, sd);
            assertNotNull(cache.get(id));
        }

        //run the query
        QueryManager qm = new RemoteQueryManager(cache);
        Set<String> queryResult = qm.queryExpiredSessions(currentTime);

        // Check that the result is correct
        assertEquals(expiredSessions.size(), queryResult.size());
        for (String s : expiredSessions)
        {
            assertTrue(queryResult.contains(s));
        }
    }
}

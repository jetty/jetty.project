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

package org.eclipse.jetty.server.session.remote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.Properties;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.SessionDataMarshaller;
import org.eclipse.jetty.util.IO;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RemoteInfinispanTestSupport
 */
public class RemoteInfinispanTestSupport
{
    private static final Logger LOG = LoggerFactory.getLogger(RemoteInfinispanTestSupport.class);
    public static final String DEFAULT_CACHE_NAME = "session_test_cache";
    public RemoteCache<String, SessionData> _cache;
    private String _name;
    public static RemoteCacheManager _manager;
    private static final Logger INFINISPAN_LOG =
        LoggerFactory.getLogger("org.eclipse.jetty.server.session.remote.infinispanLogs");

    static GenericContainer infinispan;

    static
    {
        try
        {
            //Testcontainers.exposeHostPorts(11222);
            long start = System.currentTimeMillis();
            String infinispanVersion = System.getProperty("infinispan.docker.image.version", "9.4.8.Final");
            infinispan =
                new GenericContainer(System.getProperty("infinispan.docker.image.name", "jboss/infinispan-server") +
                    ":" + infinispanVersion)
                    .withEnv("APP_USER", "theuser")
                    .withEnv("APP_PASS", "foobar")
                    .withEnv("MGMT_USER", "admin")
                    .withEnv("MGMT_PASS", "admin")
                    .waitingFor(new LogMessageWaitStrategy()
                        .withRegEx(".*Infinispan Server.*started in.*\\s"))
                    .withExposedPorts(4712, 4713, 8088, 8089, 8443, 9990, 9993, 11211, 11222, 11223, 11224)
                    .withLogConsumer(new Slf4jLogConsumer(INFINISPAN_LOG));
            infinispan.start();
            String host = infinispan.getContainerIpAddress();
            System.setProperty("hotrod.host", host);
            int port = infinispan.getMappedPort(11222);

            LOG.info("Infinispan container started for {}:{} - {}ms", host, port,
                     System.currentTimeMillis() - start);
            SearchMapping mapping = new SearchMapping();
            mapping.entity(SessionData.class).indexed().providedId()
                .property("expiry", ElementType.METHOD).field();

            Properties properties = new Properties();
            properties.put(Environment.MODEL_MAPPING, mapping);

            ConfigurationBuilder configurationBuilder = new ConfigurationBuilder().withProperties(properties)
                .addServer().host(host).port(port)
                // we just want to limit connectivity to list of host:port we knows at start
                // as infinispan create new host:port dynamically but due to how docker expose host/port we cannot do that
                .clientIntelligence(ClientIntelligence.BASIC)
                .marshaller(new ProtoStreamMarshaller());

            if (infinispanVersion.startsWith("1"))
            {
                configurationBuilder.security().authentication()
                    .realm("default")
                    .serverName("infinispan")
                    .saslMechanism("DIGEST-MD5")
                    .username("theuser").password("foobar");
            }

            Configuration configuration = configurationBuilder.build();

            _manager = new RemoteCacheManager(configuration);

            FileDescriptorSource fds = new FileDescriptorSource();
            fds.addProtoFiles("/session.proto");

            SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(_manager);
            serCtx.registerProtoFiles(fds);
            serCtx.registerMarshaller(new SessionDataMarshaller());

            ByteArrayOutputStream baos;
            try (InputStream is = RemoteInfinispanSessionDataStoreTest.class.getClassLoader().getResourceAsStream("session.proto"))
            {
                if (is == null)
                    throw new IllegalStateException("inputstream is null");

                baos = new ByteArrayOutputStream();
                IO.copy(is, baos);
            }

            String content = baos.toString("UTF-8");
            _manager.administration().getOrCreateCache("___protobuf_metadata", (String)null).put("session.proto", content);
        }
        catch (Exception e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public RemoteInfinispanTestSupport()
    {
        this(null);
    }

    public RemoteInfinispanTestSupport(String cacheName)
    {
        if (cacheName == null)
            cacheName = DEFAULT_CACHE_NAME + System.currentTimeMillis();

        _name = cacheName;
    }

    public RemoteCache<String, SessionData> getCache()
    {
        return _cache;
    }

    public void setup() throws Exception
    {
        _cache = _manager.administration().getOrCreateCache(_name, (String)null);
    }

    public void teardown() throws Exception
    {
        _cache.clear();
    }

    public void createSession(SessionData data)
        throws Exception
    {
        _cache.put(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId(), data);
    }

    public void createUnreadableSession(SessionData data)
    {

    }

    public boolean checkSessionExists(SessionData data)
        throws Exception
    {
        return (_cache.get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()) != null);
    }

    public boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        Object obj = _cache.get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;

        InfinispanSessionData saved = (InfinispanSessionData)obj;
        saved.deserializeAttributes();

        assertEquals(data.getId(), saved.getId());
        assertEquals(data.getContextPath(), saved.getContextPath());
        assertEquals(data.getVhost(), saved.getVhost());
        assertEquals(data.getAccessed(), saved.getAccessed());
        assertEquals(data.getLastAccessed(), saved.getLastAccessed());
        assertEquals(data.getCreated(), saved.getCreated());
        assertEquals(data.getCookieSet(), saved.getCookieSet());
        assertEquals(data.getLastNode(), saved.getLastNode());
        //don't test lastSaved because that is set on SessionData only after return from SessionDataStore.save()  
        assertEquals(data.getExpiry(), saved.getExpiry());
        assertEquals(data.getMaxInactiveMs(), saved.getMaxInactiveMs());

        //same number of attributes
        assertEquals(data.getAllAttributes().size(), saved.getAllAttributes().size());
        //same keys
        assertTrue(data.getKeys().equals(saved.getKeys()));
        //same values
        for (String name : data.getKeys())
        {
            assertTrue(data.getAttribute(name).equals(saved.getAttribute(name)));
        }

        return true;
    }
}

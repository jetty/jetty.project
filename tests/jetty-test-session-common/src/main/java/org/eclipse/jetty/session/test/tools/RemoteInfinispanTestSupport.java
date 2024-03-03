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

package org.eclipse.jetty.session.test.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer;
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
import org.infinispan.commons.configuration.XMLStringConfiguration;
import org.infinispan.commons.marshall.ProtoStreamMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RemoteInfinispanTestSupport
 */
public class RemoteInfinispanTestSupport
{
    private static final Logger LOG = LoggerFactory.getLogger(RemoteInfinispanTestSupport.class);
    public RemoteCache<String, InfinispanSessionData> _cache;
    private final String _name;
    public static RemoteCacheManager _manager;
    private static final Logger INFINISPAN_LOG =
            LoggerFactory.getLogger("org.eclipse.jetty.server.session.remote.infinispanLogs");

    private static final String INFINISPAN_VERSION = System.getProperty("infinispan.docker.image.version", "14.0.25.Final");
    private static final String IMAGE_NAME = System.getProperty("infinispan.docker.image.name", "infinispan/server") +
            ":" + INFINISPAN_VERSION;

    private static final GenericContainer<?> infinispan = new GenericContainer<>(IMAGE_NAME);

    static
    {
        infinispan.withEnv("USER", "theuser")
                .withEnv("PASS", "foobar")
                .withEnv("MGMT_USER", "admin")
                .withEnv("MGMT_PASS", "admin")
                .withEnv("CONFIG_PATH", "/user-config/config.yaml")
                .waitingFor(Wait.forListeningPorts(11222))
                .withExposedPorts(4712, 4713, 8088, 8089, 8443, 9990, 9993, 11211, 11222, 11223, 11224)
                .withLogConsumer(new Slf4jLogConsumer(INFINISPAN_LOG))
                .withClasspathResourceMapping("/config.yaml", "/user-config/config.yaml", BindMode.READ_ONLY)
                .start();

        // setup instance
        {

            SearchMapping mapping = new SearchMapping();
            mapping.entity(InfinispanSessionData.class).indexed().providedId()
                    .property("expiry", ElementType.METHOD).field();

            Properties properties = new Properties();
            properties.put(Environment.MODEL_MAPPING, mapping);

            ConfigurationBuilder configurationBuilder = new ConfigurationBuilder().withProperties(properties)
                    .addServer()
                    .host(infinispan.getHost())
                    .port(infinispan.getMappedPort(11222))
                    // we just want to limit connectivity to list of host:port we knows at start
                    // as infinispan create new host:port dynamically but due to how docker expose host/port we cannot do that
                    .clientIntelligence(ClientIntelligence.BASIC)
                    .marshaller(new ProtoStreamMarshaller());

            if (INFINISPAN_VERSION.startsWith("1"))
            {
                configurationBuilder.security().authentication()
                        .saslMechanism("DIGEST-MD5")
                        .username("theuser").password("foobar");
            }

            configurationBuilder.addContextInitializer(new InfinispanSerializationContextInitializer());
            Configuration configuration = configurationBuilder.build();

            _manager = new RemoteCacheManager(configuration);

            //upload the session.proto file to the remote cache
            ByteArrayOutputStream baos;
            try (InputStream is = SessionDataMarshaller.class.getClassLoader().getResourceAsStream("session.proto"))
            {
                if (is == null)
                    throw new IllegalStateException("inputstream is null");

                baos = new ByteArrayOutputStream();
                IO.copy(is, baos);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e.getMessage(), e);
            }

            String content = baos.toString(StandardCharsets.UTF_8);
            _manager.administration().getOrCreateCache("___protobuf_metadata", (String)null).put("session.proto", content);

        }

    }

    public RemoteInfinispanTestSupport(String cacheName)
    {
        Objects.requireNonNull(cacheName, "cacheName cannot be null");
        _name = cacheName;
        String xml = String.format("<infinispan>"  +
                "<cache-container>" + "<distributed-cache name=\"%s\" mode=\"SYNC\">" +
                "<encoding media-type=\"application/x-protostream\"/>" +
                "</distributed-cache>" +
                "</cache-container>" +
                "</infinispan>", _name);

        XMLStringConfiguration xmlConfig = new XMLStringConfiguration(xml);
        _cache = _manager.administration().getOrCreateCache(_name, xmlConfig);
    }

    public RemoteCache<String, InfinispanSessionData> getCache()
    {
        return _cache;
    }

    public void setup() throws Exception
    {
        // noop
    }

    public void clearCache() throws Exception
    {
        _cache.clear();
    }

    public void createSession(InfinispanSessionData data)
        throws Exception
    {
        data.serializeAttributes();
        _cache.put(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId(), data);
    }

    public void createUnreadableSession(InfinispanSessionData data)
    {
        //Unused by test
    }

    public boolean checkSessionExists(InfinispanSessionData data)
        throws Exception
    {
        return (_cache.get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()) != null);
    }

    public boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        InfinispanSessionData obj = _cache.get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;

        InfinispanSessionData saved = obj;
        if (saved.getSerializedAttributes() != null)
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
        assertEquals(data.getKeys(), saved.getKeys());
        //same values
        for (String name : data.getKeys())
        {
            assertEquals(data.getAttribute(name), saved.getAttribute(name));
        }

        return true;
    }
}

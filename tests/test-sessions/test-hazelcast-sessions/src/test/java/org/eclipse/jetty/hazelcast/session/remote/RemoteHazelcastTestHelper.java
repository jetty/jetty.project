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

package org.eclipse.jetty.hazelcast.session.remote;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.HazelcastInstance;
import org.eclipse.jetty.hazelcast.session.HazelcastSessionDataStoreFactory;
import org.eclipse.jetty.hazelcast.session.SessionDataSerializer;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemoteHazelcastTestHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(RemoteHazelcastTestHelper.class);
    private static final Logger HAZELCAST_LOG = LoggerFactory.getLogger("org.eclipse.jetty.server.session.HazelcastLogs");

    static GenericContainer HAZELCAST;

    static final String _name = Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));

    private static String host;
    private static int port;

    static
    {
        try
        {
            long start = System.currentTimeMillis();
            HAZELCAST =
                new GenericContainer("hazelcast/hazelcast:" + System.getProperty("hazelcast.version", "3.12.6"));

            HAZELCAST.withLogConsumer(new Slf4jLogConsumer(HAZELCAST_LOG)).start();
            host =  HAZELCAST.getContainerIpAddress();
            port = HAZELCAST.getMappedPort(5701);
            LOG.info("Hazelcast container started for {}:{} - {}ms", host, port,
                     System.currentTimeMillis() - start);
        }
        catch (Exception e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static SessionDataStoreFactory createSessionDataStoreFactory()
    {
        HazelcastSessionDataStoreFactory factory = new HazelcastSessionDataStoreFactory();
        factory.setOnlyClient(true);
        factory.setMapName(_name);
        factory.setHazelcastInstance(buildClient());

        return factory;
    }

    private static HazelcastInstance buildClient()
    {
        ClientNetworkConfig clientNetworkConfig = new ClientNetworkConfig()
            .setAddresses(Collections.singletonList(host + ":" + port));
        ClientConfig clientConfig = new ClientConfig()
            .setNetworkConfig(clientNetworkConfig);

        SerializerConfig sc = new SerializerConfig()
            .setImplementation(new SessionDataSerializer())
            .setTypeClass(SessionData.class);
        clientConfig.getSerializationConfig().addSerializerConfig(sc);
        return HazelcastClient.newHazelcastClient(clientConfig);
    }

    public static void createSession(SessionData data)
        throws Exception
    {
        buildClient().getMap(_name).put(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId(), data);
    }

    public static boolean checkSessionExists(SessionData data)
        throws Exception
    {
        return (buildClient().getMap(_name).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()) != null);
    }

    public static boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        Object obj = buildClient().getMap(_name).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;

        SessionData saved = (SessionData)obj;

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

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

import java.util.Collections;
import java.util.Objects;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.eclipse.jetty.hazelcast.session.HazelcastSessionDataStoreFactory;
import org.eclipse.jetty.hazelcast.session.SessionDataSerializer;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStoreFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HazelcastTestHelper
 */
public class HazelcastTestHelper
{
    private final String mapName;

    static final String _hazelcastInstanceName = "SESSION_TEST_" + System.nanoTime();

    static SerializerConfig _serializerConfig;

    static HazelcastInstance _instance;

    static
    {
        // Wire up hazelcast logging to slf4j
        System.setProperty("hazelcast.logging.class", "com.hazelcast.logging.Slf4jFactory");

        // Wire up java.util.logging (used by hazelcast libs) to slf4j.
        if (!org.slf4j.bridge.SLF4JBridgeHandler.isInstalled())
        {
            org.slf4j.bridge.SLF4JBridgeHandler.install();
        }

        _serializerConfig = new SerializerConfig().setImplementation(new SessionDataSerializer()).setTypeClass(SessionData.class);
        Config config = new Config();
        config.setInstanceName(_hazelcastInstanceName);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getSerializationConfig().addSerializerConfig(_serializerConfig);
        _instance = Hazelcast.getOrCreateHazelcastInstance(config);
    }

    public HazelcastTestHelper(String mapName)
    {
        Objects.requireNonNull(mapName, "Hazelcast mapName cannot be null");
        this.mapName = mapName;
        _instance.getConfig().addMapConfig(new MapConfig().setName(this.mapName)).setClassLoader(null);
    }

    public SessionDataStoreFactory createSessionDataStoreFactory(boolean onlyClient)
    {
        HazelcastSessionDataStoreFactory factory = new HazelcastSessionDataStoreFactory();
        factory.setOnlyClient(onlyClient);
        factory.setMapName(this.mapName);
        factory.setUseQueries(true);
        if (onlyClient)
        {
            ClientNetworkConfig clientNetworkConfig = new ClientNetworkConfig()
                .setAddresses(Collections.singletonList("localhost:" + _instance.getConfig().getNetworkConfig().getPort()));
            ClientConfig clientConfig = new ClientConfig()
                .setNetworkConfig(clientNetworkConfig);

            SerializerConfig sc = new SerializerConfig()
                    .setImplementation(new SessionDataSerializer())
                    .setTypeClass(SessionData.class);
            clientConfig.getSerializationConfig().addSerializerConfig(sc);

            factory.setHazelcastInstance(HazelcastClient.newHazelcastClient(clientConfig));
        }
        else
        {
            factory.setHazelcastInstance(_instance);
        }
        return factory;
    }

    public void tearDown()
    {
        _instance.getMap(this.mapName).clear();
    }

    public void createSession(SessionData data)
    {
        _instance.getMap(this.mapName).put(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId(), data);
    }

    public boolean checkSessionExists(SessionData data)
    {
        return (_instance.getMap(this.mapName).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()) != null);
    }

    public boolean checkSessionPersisted(SessionData data)
    {
        Object obj = _instance.getMap(this.mapName).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;

        SessionData saved = (SessionData)obj;

        assertEquals(data.getId(), saved.getId());
        assertEquals(data.getContextPath(), saved.getContextPath());
        assertEquals(data.getVhost(), saved.getVhost());
        assertEquals(data.getLastNode(), saved.getLastNode());
        assertEquals(data.getCreated(), saved.getCreated());
        assertEquals(data.getAccessed(), saved.getAccessed());
        assertEquals(data.getLastAccessed(), saved.getLastAccessed());
        assertEquals(data.getCookieSet(), saved.getCookieSet());
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

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

package org.eclipse.jetty.hazelcast.session;

import java.util.Collections;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientNetworkConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MulticastConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.util.NanoTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HazelcastTestHelper
{
    static final String _name = Long.toString(NanoTime.now());

    static final String _hazelcastInstanceName = "SESSION_TEST_" + _name;

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
        config.setNetworkConfig(new NetworkConfig().setJoin(new JoinConfig().setMulticastConfig(new MulticastConfig().setEnabled(false))));
        config.addMapConfig(new MapConfig().setName(_name)).setClassLoader(null);
        config.getSerializationConfig().addSerializerConfig(_serializerConfig);
        _instance = Hazelcast.getOrCreateHazelcastInstance(config);
    }

    public HazelcastTestHelper()
    {
        // noop
    }

    public SessionDataStoreFactory createSessionDataStoreFactory(boolean onlyClient)
    {
        HazelcastSessionDataStoreFactory factory = new HazelcastSessionDataStoreFactory();
        factory.setOnlyClient(onlyClient);
        factory.setMapName(_name);
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
        _instance.getMap(_name).clear();
    }

    public void createSession(SessionData data)
    {
        Object o = _instance.getMap(_name).put(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId(), data);
    }

    public boolean checkSessionExists(SessionData data)
    {
        return (_instance.getMap(_name).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()) != null);
    }

    public boolean checkSessionPersisted(SessionData data)
    {
        Object obj = _instance.getMap(_name).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
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
        assertTrue(data.getKeys().equals(saved.getKeys()));
        //same values
        for (String name : data.getKeys())
        {
            assertTrue(data.getAttribute(name).equals(saved.getAttribute(name)));
        }
        return true;
    }
}

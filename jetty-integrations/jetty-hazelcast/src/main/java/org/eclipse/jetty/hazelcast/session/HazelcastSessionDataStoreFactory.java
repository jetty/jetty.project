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

import java.io.IOException;
import java.util.Arrays;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.SerializerConfig;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.SessionManager;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory to construct {@link HazelcastSessionDataStore}
 */
public class HazelcastSessionDataStoreFactory
    extends AbstractSessionDataStoreFactory
    implements SessionDataStoreFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(HazelcastSessionDataStoreFactory.class);
    
    private String hazelcastInstanceName = "JETTY_DISTRIBUTED_SESSION_INSTANCE";

    private boolean onlyClient;

    private String configurationLocation;

    private String mapName = "jetty-distributed-session-map";

    private HazelcastInstance hazelcastInstance;

    private MapConfig mapConfig;

    private boolean useQueries = false;

    private String addresses;

    public boolean isUseQueries()
    {
        return useQueries;
    }

    public void setUseQueries(boolean useQueries)
    {
        this.useQueries = useQueries;
    }

    @Override
    public SessionDataStore getSessionDataStore(SessionManager sessionManager)
        throws Exception
    {
        HazelcastSessionDataStore hazelcastSessionDataStore = new HazelcastSessionDataStore();

        if (hazelcastInstance == null)
        {
            try
            {
                if (onlyClient)
                {
                    ClientConfig config;
                    if (StringUtil.isEmpty(configurationLocation))
                    {
                        config = new ClientConfig();

                        if (addresses != null && !addresses.isEmpty())
                        {
                            config.getNetworkConfig().setAddresses(Arrays.asList(addresses.split(",")));
                        }

                        SerializerConfig sc = new SerializerConfig()
                            .setImplementation(new SessionDataSerializer())
                            .setTypeClass(SessionData.class);
                        config.getSerializationConfig().addSerializerConfig(sc);
                    }
                    else
                    {
                        config = new XmlClientConfigBuilder(configurationLocation).build();
                        if (config.getSerializationConfig().getSerializerConfigs().stream().noneMatch(s ->
                            SessionData.class.getName().equals(s.getTypeClassName()) && s.getImplementation() instanceof SessionDataSerializer))
                            LOG.warn("Hazelcast xml config is missing org.eclipse.jetty.hazelcast.session.SessionDataSerializer - sessions may not serialize correctly");
                    }
                    
                    hazelcastInstance = HazelcastClient.newHazelcastClient(config);
                }
                else
                {
                    Config config;
                    if (StringUtil.isEmpty(configurationLocation))
                    {
                        SerializerConfig sc = new SerializerConfig()
                            .setImplementation(new SessionDataSerializer())
                            .setTypeClass(SessionData.class);
                        config = new Config();
                        config.getSerializationConfig().addSerializerConfig(sc);
                        // configure a default Map if null
                        if (mapConfig == null)
                        {
                            mapConfig = new MapConfig();
                            mapConfig.setName(mapName);
                        }
                        else
                        {
                            // otherwise we reuse the name
                            mapName = mapConfig.getName();
                        }
                        config.addMapConfig(mapConfig);
                    }
                    else
                    {
                        config = new XmlConfigBuilder(configurationLocation).build();
                        if (config.getSerializationConfig().getSerializerConfigs().stream().noneMatch(s ->
                            SessionData.class.getName().equals(s.getTypeClassName()) && s.getImplementation() instanceof SessionDataSerializer))
                            LOG.warn("Hazelcast xml config is missing org.eclipse.jetty.hazelcast.session.SessionDataSerializer - sessions may not serialize correctly");
                    }
                    config.setInstanceName(hazelcastInstanceName);
                    hazelcastInstance = Hazelcast.getOrCreateHazelcastInstance(config);
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
        // initialize the map
        hazelcastSessionDataStore.setSessionDataMap(hazelcastInstance.getMap(mapName));
        hazelcastSessionDataStore.setGracePeriodSec(getGracePeriodSec());
        hazelcastSessionDataStore.setSavePeriodSec(getSavePeriodSec());
        hazelcastSessionDataStore.setUseQueries(isUseQueries());
        return hazelcastSessionDataStore;
    }

    public boolean isOnlyClient()
    {
        return onlyClient;
    }

    /**
     * @param onlyClient if <code>true</code> the session manager will only connect to an external Hazelcast instance
     * and not use this JVM to start an Hazelcast instance
     */
    public void setOnlyClient(boolean onlyClient)
    {
        this.onlyClient = onlyClient;
    }

    public String getConfigurationLocation()
    {
        return configurationLocation;
    }

    public void setConfigurationLocation(String configurationLocation)
    {
        this.configurationLocation = configurationLocation;
    }

    public String getMapName()
    {
        return mapName;
    }

    public void setMapName(String mapName)
    {
        this.mapName = mapName;
    }

    public HazelcastInstance getHazelcastInstance()
    {
        return hazelcastInstance;
    }

    public void setHazelcastInstance(HazelcastInstance hazelcastInstance)
    {
        this.hazelcastInstance = hazelcastInstance;
    }

    public MapConfig getMapConfig()
    {
        return mapConfig;
    }

    public void setMapConfig(MapConfig mapConfig)
    {
        this.mapConfig = mapConfig;
    }

    public String getHazelcastInstanceName()
    {
        return hazelcastInstanceName;
    }

    public void setHazelcastInstanceName(String hazelcastInstanceName)
    {
        this.hazelcastInstanceName = hazelcastInstanceName;
    }

    public String getAddresses()
    {
        return addresses;
    }

    public void setAddresses(String addresses)
    {
        this.addresses = addresses;
    }
}

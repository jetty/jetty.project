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

package org.eclipse.jetty.hazelcast.session;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;

import java.io.IOException;

/**
 * Factory to construct {@link HazelcastSessionDataStore}
 */
public class HazelcastSessionDataStoreFactory
    extends AbstractSessionDataStoreFactory
    implements SessionDataStoreFactory
{

    private String hazelcastInstanceName = "JETTY_DISTRIBUTED_SESSION_INSTANCE";

    private boolean onlyClient;

    private String configurationLocation;

    private String mapName = "jetty-distributed-session-map";

    private HazelcastInstance hazelcastInstance;

    private MapConfig mapConfig;


    @Override
    public SessionDataStore getSessionDataStore( SessionHandler handler )
        throws Exception
    {
        HazelcastSessionDataStore hazelcastSessionDataStore = new HazelcastSessionDataStore();

        if ( hazelcastInstance == null )
        {
            try
            {
                if ( onlyClient )
                {
                    if ( configurationLocation == null )
                    {
                        hazelcastInstance = HazelcastClient.newHazelcastClient( new ClientConfig() );
                    }
                    else
                    {
                        hazelcastInstance = HazelcastClient.newHazelcastClient(
                            new XmlClientConfigBuilder( configurationLocation ).build() );
                    }

                }
                else
                {
                    Config config;
                    if ( configurationLocation == null )
                    {
                        config = new Config();
                        // configure a default Map if null
                        if ( mapConfig == null )
                        {
                            mapConfig = new MapConfig();
                            mapConfig.setName( mapName );
                        }
                        else
                        {
                            // otherwise we reuse the name
                            mapName = mapConfig.getName();
                        }
                        config.addMapConfig( mapConfig );
                    }
                    else
                    {
                        config = new XmlConfigBuilder( configurationLocation ).build();
                    }
                    config.setInstanceName( hazelcastInstanceName );
                    hazelcastInstance = Hazelcast.getOrCreateHazelcastInstance( config );
                }
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e.getMessage(), e );
            }
        }
        // initialize the map
        hazelcastSessionDataStore.setSessionDataMap(hazelcastInstance.getMap( mapName ) );
        hazelcastSessionDataStore.setGracePeriodSec( getGracePeriodSec() );
        hazelcastSessionDataStore.setSavePeriodSec( getSavePeriodSec() );
        return hazelcastSessionDataStore;
    }

    public boolean isOnlyClient()
    {
        return onlyClient;
    }

    /**
     *
     * @param onlyClient if <code>true</code> the session manager will only connect to an external Hazelcast instance
     *                   and not use this JVM to start an Hazelcast instance
     */
    public void setOnlyClient( boolean onlyClient )
    {
        this.onlyClient = onlyClient;
    }

    public String getConfigurationLocation()
    {
        return configurationLocation;
    }

    public void setConfigurationLocation( String configurationLocation )
    {
        this.configurationLocation = configurationLocation;
    }

    public String getMapName()
    {
        return mapName;
    }

    public void setMapName( String mapName )
    {
        this.mapName = mapName;
    }

    public HazelcastInstance getHazelcastInstance()
    {
        return hazelcastInstance;
    }

    public void setHazelcastInstance( HazelcastInstance hazelcastInstance )
    {
        this.hazelcastInstance = hazelcastInstance;
    }

    public MapConfig getMapConfig()
    {
        return mapConfig;
    }

    public void setMapConfig( MapConfig mapConfig )
    {
        this.mapConfig = mapConfig;
    }

    public String getHazelcastInstanceName()
    {
        return hazelcastInstanceName;
    }

    public void setHazelcastInstanceName( String hazelcastInstanceName )
    {
        this.hazelcastInstanceName = hazelcastInstanceName;
    }
}

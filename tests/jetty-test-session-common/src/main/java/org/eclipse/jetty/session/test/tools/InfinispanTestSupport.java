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

import java.lang.annotation.ElementType;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.toolchain.test.FS;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.ConfigurationChildBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.eviction.EvictionStrategy;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * InfinispanTestSupport
 */
public class InfinispanTestSupport
{
    public Cache<String, InfinispanSessionData> _cache;

    public ConfigurationBuilder _builder;
    private boolean _useFileStore;
    private boolean _serializeSessionData;
    private final String _name;
    public static EmbeddedCacheManager _manager;

    static
    {
        try
        {
            _manager = new DefaultCacheManager(new GlobalConfigurationBuilder().jmx()
                .serialization()
                .addContextInitializer(new InfinispanSerializationContextInitializer())
                .build());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public InfinispanTestSupport(String cacheName)
    {
        Objects.requireNonNull(cacheName, "cacheName cannot be null");

        _name = cacheName;
        _builder = new ConfigurationBuilder();
    }

    public void setUseFileStore(boolean useFileStore)
    {
        _useFileStore = useFileStore;
    }

    public void setSerializeSessionData(boolean serializeSessionData)
    {
        _serializeSessionData = serializeSessionData;
    }
    
    public Cache<String, InfinispanSessionData> getCache()
    {
        return _cache;
    }

    public void setup(Path root) throws Exception
    {
        Path indexesDir = root.resolve("indexes");
        Path tmpdir = root.resolve("tmp");
        FS.ensureDirExists(indexesDir);

        SearchMapping mapping = new SearchMapping();
        mapping.entity(SessionData.class).indexed().providedId().property("expiry", ElementType.FIELD).field();
        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);
        properties.put("hibernate.search.default.indexBase", indexesDir.toString());
        properties.put("hibernate.cache.infinispan.entity.eviction.strategy", "NONE");

        if (_manager.cacheExists(_name))
        {
            _manager.administration().removeCache(_name);
        }

        if (_useFileStore)
        {
            ConfigurationChildBuilder b = _builder
                .indexing()
                .addIndexedEntity(SessionData.class)
                // FIXME
                //.withProperties(properties)
                .memory()
                .whenFull(EvictionStrategy.NONE)
                .persistence()
                .addSoftIndexFileStore()
                .segmented(false)
                .indexLocation(tmpdir.toFile().getAbsolutePath());
            if (_serializeSessionData)
            {
                b = b.memory().storage(StorageType.HEAP)
                    .encoding()
                    .mediaType("application/x-protostream");
            }
            _manager.defineConfiguration(_name, b.build());
        }
        else
        {
            ConfigurationChildBuilder b = _builder.indexing()
                    // FIXME
                    //.withProperties(properties)
                .addIndexedEntity(SessionData.class);
        
            if (_serializeSessionData)
            {
                b = b.memory().storage(StorageType.HEAP)
                    .encoding()
                    .mediaType("application/x-protostream");
            }
                
            _manager.defineConfiguration(_name, b.build());
        }
        _cache = _manager.getCache(_name);
    }

    public void clearCache() throws Exception
    {
        _cache.clear();
        _manager.administration().removeCache(_name);
    }

    public void createSession(InfinispanSessionData data)
        throws Exception
    {
        ((InfinispanSessionData)data).serializeAttributes();
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
        //evicts the object from memory. Forces the cache to fetch the data from file
        if (_useFileStore)
        {
            _cache.evict(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        }

        SessionData obj = _cache.get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;
        
        if (obj instanceof InfinispanSessionData isd)
        {
            if (isd.getSerializedAttributes() != null)
                isd.deserializeAttributes();
        }

        //turn an Entity into a Session
        assertEquals(data.getId(), obj.getId());
        assertEquals(data.getContextPath(), obj.getContextPath());
        assertEquals(data.getVhost(), obj.getVhost());
        assertEquals(data.getAccessed(), obj.getAccessed());
        assertEquals(data.getLastAccessed(), obj.getLastAccessed());
        assertEquals(data.getCreated(), obj.getCreated());
        assertEquals(data.getCookieSet(), obj.getCookieSet());
        assertEquals(data.getLastNode(), obj.getLastNode());
        //don't test lastSaved, because that is set only on the SessionData after it returns from SessionDataStore.save()
        assertEquals(data.getExpiry(), obj.getExpiry());
        assertEquals(data.getMaxInactiveMs(), obj.getMaxInactiveMs());

        
        //same number of attributes
        assertEquals(data.getAllAttributes().size(), obj.getAllAttributes().size());
        //same keys
        assertEquals(data.getKeys(), obj.getKeys());
        //same values
        for (String name : data.getKeys())
        {
            assertEquals(data.getAttribute(name), obj.getAttribute(name));
        }

        return true;
    }
}

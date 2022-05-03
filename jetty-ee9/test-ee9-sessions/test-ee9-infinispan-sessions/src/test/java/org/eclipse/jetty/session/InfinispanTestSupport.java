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

package org.eclipse.jetty.session;

import java.io.File;
import java.lang.annotation.ElementType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.eclipse.jetty.session.infinispan.InfinispanSerializationContextInitializer;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.IO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InfinispanTestSupport
 */
public class InfinispanTestSupport
{
    public static final String DEFAULT_CACHE_NAME = "session_test_cache";
    public Cache _cache;

    public ConfigurationBuilder _builder;
    private File _tmpdir;
    private boolean _useFileStore;
    private boolean _serializeSessionData;
    private String _name;
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

    public InfinispanTestSupport()
    {
        this(null);
    }

    public InfinispanTestSupport(String cacheName)
    {
        if (cacheName == null)
            cacheName = DEFAULT_CACHE_NAME + System.currentTimeMillis();

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
    
    public Cache getCache()
    {
        return _cache;
    }

    public void setup(Path root) throws Exception
    {
        Path indexesDir = root.resolve("indexes");
        FS.ensureDirExists(indexesDir);

        SearchMapping mapping = new SearchMapping();
        mapping.entity(SessionData.class).indexed().providedId().property("expiry", ElementType.FIELD).field();
        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);
        properties.put("hibernate.search.default.indexBase", indexesDir.toString());
        properties.put("hibernate.cache.infinispan.entity.eviction.strategy", "NONE");

        if (_useFileStore)
        {
            Path tmpDir = Files.createTempDirectory("infinispan");
            _tmpdir = tmpDir.toFile();

            ConfigurationChildBuilder b = _builder
                .indexing()
                .addIndexedEntity(SessionData.class)
                .withProperties(properties)
                .memory()
                .whenFull(EvictionStrategy.NONE)
                .persistence()
                .addSingleFileStore()
                .segmented(false)
                .location(_tmpdir.getAbsolutePath());
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
                .withProperties(properties)
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

    public void teardown() throws Exception
    {
        _cache.clear();
        _manager.administration().removeCache(_name);
        if (_useFileStore)
        {
            if (_tmpdir != null)
            {
                IO.delete(_tmpdir);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void createSession(SessionData data)
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

        Object obj = _cache.get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;

        SessionData saved = (SessionData)obj;
        
        if (saved instanceof InfinispanSessionData)
        {
            InfinispanSessionData isd = (InfinispanSessionData)saved;
            if (isd.getSerializedAttributes() != null)
                isd.deserializeAttributes();
        }

        //turn an Entity into a Session
        assertEquals(data.getId(), saved.getId());
        assertEquals(data.getContextPath(), saved.getContextPath());
        assertEquals(data.getVhost(), saved.getVhost());
        assertEquals(data.getAccessed(), saved.getAccessed());
        assertEquals(data.getLastAccessed(), saved.getLastAccessed());
        assertEquals(data.getCreated(), saved.getCreated());
        assertEquals(data.getCookieSet(), saved.getCookieSet());
        assertEquals(data.getLastNode(), saved.getLastNode());
        //don't test lastSaved, because that is set only on the SessionData after it returns from SessionDataStore.save()
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

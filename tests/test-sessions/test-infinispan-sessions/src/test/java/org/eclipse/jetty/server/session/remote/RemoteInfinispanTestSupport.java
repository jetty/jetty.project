//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty. Ltd.
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
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RemoteInfinispanTestSupport
 */
public class RemoteInfinispanTestSupport
{
    public static final String DEFAULT_CACHE_NAME = "session_test_cache";
    public RemoteCache<String, SessionData> _cache;
    private String _name;
    public static RemoteCacheManager _manager;

    static
    {
        try
        {
            String host = System.getProperty("hotrod.host", "127.0.0.1");

            SearchMapping mapping = new SearchMapping();
            mapping.entity(SessionData.class).indexed().providedId()
                .property("expiry", ElementType.METHOD).field();

            Properties properties = new Properties();
            properties.put(Environment.MODEL_MAPPING, mapping);

            ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
            clientBuilder.withProperties(properties).addServer().host(host).marshaller(new ProtoStreamMarshaller());

            _manager = new RemoteCacheManager(clientBuilder.build());

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
            _manager.getCache("___protobuf_metadata").put("session.proto", content);
        }
        catch (Exception e)
        {
            fail(e);
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
        _cache = _manager.getCache(_name);
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

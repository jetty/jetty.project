//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.session.infinispan;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.session.infinispan.RemoteQueryManager;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemoteQueryManagerTest
{
    public static final String DEFAULT_CACHE_NAME = "remote-session-test";

    @Test
    public void test() throws Exception
    {
        SearchMapping mapping = new SearchMapping();
        mapping.entity(SessionData.class).indexed().providedId().property("expiry", ElementType.FIELD).field();

        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);

        ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
        clientBuilder.withProperties(properties).addServer().host("127.0.0.1").marshaller(new ProtoStreamMarshaller());

        RemoteCacheManager remoteCacheManager = new RemoteCacheManager(clientBuilder.build());

        FileDescriptorSource fds = new FileDescriptorSource();
        fds.addProtoFiles("/session.proto");

        SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(remoteCacheManager);
        serCtx.registerProtoFiles(fds);
        serCtx.registerMarshaller(new SessionDataMarshaller());

        RemoteCache<String, SessionData> _cache = remoteCacheManager.getCache(DEFAULT_CACHE_NAME);

        ByteArrayOutputStream baos;
        try (InputStream is = RemoteQueryManagerTest.class.getClassLoader().getResourceAsStream("session.proto"))
        {
            if (is == null)
                throw new IllegalStateException("inputstream is null");

            baos = new ByteArrayOutputStream();
            IO.copy(is, baos);
        }

        String content = baos.toString("UTF-8");
        remoteCacheManager.getCache("___protobuf_metadata").put("session.proto", content);

        //put some sessions into the remote cache
        int numSessions = 10;
        long currentTime = 500;
        int maxExpiryTime = 1000;
        Set<String> expiredSessions = new HashSet<>();
        Random r = new Random();

        for (int i = 0; i < numSessions; i++)
        {
            String id = "sd" + i;
            //create new sessiondata with random expiry time
            long expiryTime = r.nextInt(maxExpiryTime);
            SessionData sd = new SessionData(id, "", "", 0, 0, 0, 0);
            sd.setLastNode("lastNode");
            sd.setExpiry(expiryTime);

            //if this entry has expired add it to expiry list
            if (expiryTime <= currentTime)
                expiredSessions.add(id);

            //add to cache
            _cache.put(id, sd);
            assertNotNull(_cache.get(id));
        }

        //run the query
        QueryManager qm = new RemoteQueryManager(_cache);
        Set<String> queryResult = qm.queryExpiredSessions(currentTime);

        // Check that the result is correct
        assertEquals(expiredSessions.size(), queryResult.size());
        for (String s : expiredSessions)
        {
            assertTrue(queryResult.contains(s));
        }
    }
}

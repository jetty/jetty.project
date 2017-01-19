//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.gcloud.memcached.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import org.eclipse.jetty.gcloud.session.GCloudSessionManager;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

import com.google.api.client.util.Strings;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreFactory;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.GqlQuery;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.ProjectionEntity;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.Query.ResultType;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.StructuredQuery;
import com.google.cloud.datastore.testing.LocalDatastoreHelper;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;

/**
 * GCloudMemcachedSessionTestSupport
 *
 *
 */
public class GCloudMemcachedSessionTestSupport
{

    /**
     * MemcachedFlusher
     *
     *
     */
    public static class MemcachedFlusher 
    {
        protected XMemcachedClientBuilder _builder;
        protected MemcachedClient _client;


        public MemcachedFlusher() throws Exception
        {        
            _builder = new XMemcachedClientBuilder("localhost:11211");
            _client = _builder.build();
        }
        
        public void flush () throws Exception
        {
            _client.flushAllWithNoReply();
        }
    }
    
    
    MemcachedFlusher _flusher;
    LocalDatastoreHelper _helper = LocalDatastoreHelper.create(1.0);
    Datastore _ds;
    
   
    
    public GCloudMemcachedSessionTestSupport ()
    {
        DatastoreOptions options = _helper.options();
        _ds = options.service();
    }

    
    public Datastore getDatastore()
    {
        return _ds;
    }
    
    public void setUp()
    throws Exception
    {
        _helper.start();
        _flusher = new MemcachedFlusher();
    }
    
    
   
    
    public void tearDown()
    throws Exception
    {
        _helper.stop();
        _flusher.flush();
    }

    
    public void listSessions () throws Exception
    {
        GqlQuery.Builder builder = Query.gqlQueryBuilder(ResultType.ENTITY, "select * from "+GCloudSessionManager.KIND);
       
        Query<Entity> query = builder.build();
    
        QueryResults<Entity> results = _ds.run(query);
        assertNotNull(results);
        System.err.println("SESSIONS::::::::");
        while (results.hasNext())
        {
            
            Entity e = results.next();
            System.err.println(e.getString("clusterId")+" expires at "+e.getLong("expiry"));
        }
        System.err.println("END OF SESSIONS::::::::");
    }
    
   
    public void assertSessions(int count) throws Exception
    {
        Query<Key> query = Query.keyQueryBuilder().kind(GCloudSessionManager.KIND).build();
        QueryResults<Key> results = _ds.run(query);
        assertNotNull(results);
        int actual = 0;
        while (results.hasNext())
        { 
            results.next();
            ++actual;
        }       
        assertEquals(count, actual);
    }

    public void deleteSessions () throws Exception
    {

        _flusher.flush();
        Query<Key> query = Query.keyQueryBuilder().kind(GCloudSessionManager.KIND).build();
        QueryResults<Key> results = _ds.run(query);

        if (results != null)
        {
            List<Key> keys = new ArrayList<Key>();

            while (results.hasNext())
            { 
                keys.add(results.next());
            }

            _ds.delete(keys.toArray(new Key[keys.size()]));

        }

        assertSessions(0);
    }
}

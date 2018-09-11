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


package org.eclipse.jetty.gcloud.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.gcloud.session.GCloudSessionDataStore.EntityDataModel;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.threeten.bp.Duration;

import com.google.cloud.datastore.Blob;
import com.google.cloud.datastore.BlobValue;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.GqlQuery;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.KeyFactory;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.Query.ResultType;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.StructuredQuery.PropertyFilter;
import com.google.cloud.datastore.testing.LocalDatastoreHelper;

/**
 * GCloudSessionTestSupport
 *
 *
 */
public class GCloudSessionTestSupport
{ 
    LocalDatastoreHelper _helper = LocalDatastoreHelper.create(1.0);
    Datastore _ds;
    KeyFactory _keyFactory;

    
    public static class TestGCloudSessionDataStoreFactory extends GCloudSessionDataStoreFactory
    {
        Datastore _d;
        
        public TestGCloudSessionDataStoreFactory(Datastore d)
        {
            _d = d;
        }
        /** 
         * @see org.eclipse.jetty.gcloud.session.GCloudSessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
         */
        @Override
        public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
        {
            GCloudSessionDataStore ds = (GCloudSessionDataStore)super.getSessionDataStore(handler);
            ds.setMaxRetries(GCloudSessionDataStore.DEFAULT_MAX_RETRIES);
            ds.setDatastore(_d);
            return ds;
        }

    }
    public static GCloudSessionDataStoreFactory newSessionDataStoreFactory(Datastore d)
    {
       return new TestGCloudSessionDataStoreFactory(d);
    }

    public GCloudSessionTestSupport ()
    {
        DatastoreOptions options = _helper.getOptions();
        _ds = options.getService();
        _keyFactory =_ds.newKeyFactory().setKind(EntityDataModel.KIND); 
    }



    public void setUp()
            throws Exception
    {
        _helper.start();
    }


    public Datastore getDatastore ()
    {
        return _ds;
    }


    public void tearDown()
            throws Exception
    {
        _helper.stop(Duration.ofMinutes(1)); //wait up to 1min for shutdown
    }
    
    
    public void reset() throws Exception
    {
        _helper.reset();
    }
    
    public void createSession (String id, String contextPath, String vhost, 
                                      String lastNode, long created, long accessed, 
                                      long lastAccessed, long maxIdle, long expiry,
                                      long cookieset, long lastSaved,
                                      Map<String,Object> attributes)
    throws Exception
    {
        //serialize the attribute map
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (attributes != null)
        {
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(attributes);
            oos.flush();
        }

        //turn a session into an entity         
        Entity.Builder builder = Entity.newBuilder(_keyFactory.newKey(contextPath+"_"+vhost+"_"+id))
                .set(EntityDataModel.ID, id)
                .set(EntityDataModel.CONTEXTPATH, contextPath)
                .set(EntityDataModel.VHOST, vhost)
                .set(EntityDataModel.ACCESSED, accessed)
                .set(EntityDataModel.LASTACCESSED, lastAccessed)
                .set(EntityDataModel.CREATETIME, created)
                .set(EntityDataModel.COOKIESETTIME, cookieset)
                .set(EntityDataModel.LASTNODE, lastNode)
                .set(EntityDataModel.EXPIRY, expiry)
                .set(EntityDataModel.MAXINACTIVE, maxIdle)
                .set(EntityDataModel.LASTSAVED, lastSaved);
        if (attributes != null)
                builder.set(EntityDataModel.ATTRIBUTES, BlobValue.newBuilder(Blob.copyFrom(baos.toByteArray())).setExcludeFromIndexes(true).build());

        Entity entity = builder.build();
        
        _ds.put(entity);
    }
    
    public boolean checkSessionPersisted (SessionData data)
    throws Exception
    {
        Entity entity = _ds.get(_keyFactory.newKey(data.getContextPath()+"_"+data.getVhost()+"_"+data.getId()));
        if (entity == null)
            return false;
        
        //turn an Entity into a Session
        assertEquals(data.getId(), entity.getString(EntityDataModel.ID));
        assertEquals(data.getContextPath(), entity.getString(EntityDataModel.CONTEXTPATH));
        assertEquals(data.getVhost(), entity.getString(EntityDataModel.VHOST));
        assertEquals(data.getAccessed(), entity.getLong(EntityDataModel.ACCESSED));
        assertEquals(data.getLastAccessed(), entity.getLong(EntityDataModel.LASTACCESSED));
        assertEquals(data.getCreated(), entity.getLong(EntityDataModel.CREATETIME));
        assertEquals(data.getCookieSet(), entity.getLong(EntityDataModel.COOKIESETTIME));
        assertEquals(data.getLastNode(), entity.getString(EntityDataModel.LASTNODE));
        assertEquals(data.getLastSaved(), entity.getLong(EntityDataModel.LASTSAVED));  
        assertEquals(data.getExpiry(), entity.getLong(EntityDataModel.EXPIRY));
        assertEquals(data.getMaxInactiveMs(), entity.getLong(EntityDataModel.MAXINACTIVE));
        Blob blob = (Blob) entity.getBlob(EntityDataModel.ATTRIBUTES);
        
        Map<String,Object> attributes = new HashMap<>();
        try (ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(blob.asInputStream()))
        {
            Object o = ois.readObject();
            attributes.putAll((Map<String,Object>)o);
        }
        
        //same number of attributes
        assertEquals(data.getAllAttributes().size(), attributes.size());
        //same keys
        assertTrue(data.getKeys().equals(attributes.keySet()));
        //same values
        for (String name:data.getKeys())
        {
            assertTrue(data.getAttribute(name).equals(attributes.get(name)));
        }
        
        return true;
    }
    
    
    public  boolean checkSessionExists (String id)
    throws Exception
    {
        Query<Entity> query = Query.newEntityQueryBuilder()
                .setKind(EntityDataModel.KIND)
                .setFilter(PropertyFilter.eq(EntityDataModel.ID, id))
                .build();
        
        QueryResults<Entity> results = _ds.run(query);
           
       
        if (results.hasNext())
        {
           return true;
        }
        
        return false;
    }
    
    public Set<String> getSessionIds () throws Exception
    {
        HashSet<String> ids = new HashSet<String>();
        GqlQuery.Builder builder = Query.newGqlQueryBuilder(ResultType.ENTITY, "select * from "+GCloudSessionDataStore.EntityDataModel.KIND);
       
        Query<Entity> query = builder.build();
    
        QueryResults<Entity> results = _ds.run(query);
        assertNotNull(results);
        while (results.hasNext())
        {
            Entity e = results.next();
            ids.add(e.getString("id"));
        }
        
        return ids;
    }
    
    public void listSessions () throws Exception
    {

        GqlQuery.Builder builder = Query.newGqlQueryBuilder(ResultType.ENTITY, "select * from "+GCloudSessionDataStore.EntityDataModel.KIND);
       
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
        Query<Key> query = Query.newKeyQueryBuilder().setKind(GCloudSessionDataStore.EntityDataModel.KIND).build();
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
        Query<Key> query = Query.newKeyQueryBuilder().setKind(GCloudSessionDataStore.EntityDataModel.KIND).build();
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

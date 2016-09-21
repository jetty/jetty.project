//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.GqlQuery;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.Query.ResultType;
import com.google.cloud.datastore.QueryResults;
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



    public GCloudSessionTestSupport ()
    {
        DatastoreOptions options = _helper.options();
        _ds = options.service();
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
        _helper.stop();
    }
    
    
    
    public Set<String> getSessionIds () throws Exception
    {
        HashSet<String> ids = new HashSet<String>();
        GqlQuery.Builder builder = Query.gqlQueryBuilder(ResultType.ENTITY, "select * from "+GCloudSessionDataStore.EntityDataModel.KIND);
       
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

        GqlQuery.Builder builder = Query.gqlQueryBuilder(ResultType.ENTITY, "select * from "+GCloudSessionDataStore.EntityDataModel.KIND);
       
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
        Query<Key> query = Query.keyQueryBuilder().kind(GCloudSessionDataStore.EntityDataModel.KIND).build();
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
        Query<Key> query = Query.keyQueryBuilder().kind(GCloudSessionDataStore.EntityDataModel.KIND).build();
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

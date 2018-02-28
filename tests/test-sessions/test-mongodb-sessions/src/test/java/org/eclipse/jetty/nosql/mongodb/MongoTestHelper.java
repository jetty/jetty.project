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

package org.eclipse.jetty.nosql.mongodb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.UnknownHostException;
import java.sql.Date;
import java.util.HashMap;
import java.util.Map;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;


/**
 * MongoTestHelper
 * 
 */
public class MongoTestHelper
{
    static int __workers=0;
    public static final String DB_NAME = "HttpSessions";
    public static final String COLLECTION_NAME = "testsessions";
    
    
    
    public static void dropCollection () throws MongoException, UnknownHostException
    {
        new Mongo().getDB(DB_NAME).getCollection(COLLECTION_NAME).drop();
    }
    
    
    public static void createCollection() throws UnknownHostException, MongoException
    {
        new Mongo().getDB(DB_NAME).createCollection(COLLECTION_NAME, null);
    }
    
    
    public static DBCollection getCollection () throws UnknownHostException, MongoException 
    {
        return new Mongo().getDB(DB_NAME).getCollection(COLLECTION_NAME);
    }
    
    
    public static MongoSessionDataStoreFactory newSessionDataStoreFactory()
    {
        MongoSessionDataStoreFactory storeFactory = new MongoSessionDataStoreFactory();
        storeFactory.setCollectionName(COLLECTION_NAME);
        storeFactory.setDbName(DB_NAME);
        return storeFactory;
    }

    public static void createSession (String id, String contextPath, String vhost, 
                                      String lastNode, long created, long accessed, 
                                      long lastAccessed, long maxIdle, long expiry,
                                      Map<String,Object> attributes)
                                              throws Exception
    {

        DBCollection collection = new Mongo().getDB(DB_NAME).getCollection(COLLECTION_NAME);

        // Form query for upsert
        BasicDBObject key = new BasicDBObject(MongoSessionDataStore.__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();
        BasicDBObject unsets = new BasicDBObject();

        Object version = new Long(1);

        // New session

        upsert = true;
        sets.put(MongoSessionDataStore.__CREATED,created);
        sets.put(MongoSessionDataStore.__VALID,true);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath +"."+MongoSessionDataStore.__VERSION,version);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath +"."+MongoSessionDataStore.__LASTSAVED, System.currentTimeMillis());
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath +"."+MongoSessionDataStore.__LASTNODE, lastNode);
        sets.put(MongoSessionDataStore.__MAX_IDLE, maxIdle);
        sets.put(MongoSessionDataStore.__EXPIRY, expiry);
        sets.put(MongoSessionDataStore.__ACCESSED, accessed);
        sets.put(MongoSessionDataStore.__LAST_ACCESSED, lastAccessed);

        if (attributes != null)
        {
            for (String name : attributes.keySet())
            {
                Object value = attributes.get(name);
                sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath+ "." + encodeName(name),encodeName(value));
            }
        }
        update.put("$set",sets);
        WriteResult res = collection.update(key,update,upsert,false,WriteConcern.SAFE);
    }
    
    protected static String encodeName(String name)
    {
        return name.replace("%","%25").replace(".","%2E");
    }
    protected static Object encodeName(Object value) throws IOException
    {
        if (value instanceof Number || value instanceof String || value instanceof Boolean || value instanceof Date)
        {
            return value;
        }
        else if (value.getClass().equals(HashMap.class))
        {
            BasicDBObject o = new BasicDBObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet())
            {
                if (!(entry.getKey() instanceof String))
                {
                    o = null;
                    break;
                }
                o.append(encodeName(entry.getKey().toString()),encodeName(entry.getValue()));
            }

            if (o != null)
                return o;
        }
        
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bout);
        out.reset();
        out.writeUnshared(value);
        out.flush();
        return bout.toByteArray();
    }
    
    
}

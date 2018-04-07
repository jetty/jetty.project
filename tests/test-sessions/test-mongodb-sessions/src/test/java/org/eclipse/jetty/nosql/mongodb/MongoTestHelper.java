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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.UnknownHostException;
import java.sql.Date;
import java.util.HashMap;
import java.util.Map;

import com.mongodb.MongoClient;
import org.eclipse.jetty.nosql.NoSqlSessionDataStore.NoSqlSessionData;
import org.eclipse.jetty.server.session.SessionData;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * MongoTestHelper
 * 
 */
public class MongoTestHelper
{
    private final static Logger LOG = Log.getLogger(MongoTestHelper.class);
    static int __workers=0;
    public static final String DB_NAME = "HttpSessions";
    public static final String COLLECTION_NAME = "testsessions";

    static MongoClient _mongoClient;
    static
    {
        try
        {
            _mongoClient =
                new MongoClient( System.getProperty( "embedmongo.host" ), Integer.getInteger( "embedmongoPort" ) );
        }
        catch ( UnknownHostException e )
        {
            e.printStackTrace();
        }
    }
    
    
    public static void dropCollection () throws MongoException, UnknownHostException
    {
        _mongoClient.getDB(DB_NAME).getCollection(COLLECTION_NAME).drop();
    }
    
    
    public static void createCollection() throws UnknownHostException, MongoException
    {
        _mongoClient.getDB(DB_NAME).createCollection(COLLECTION_NAME, null);
    }
    
    
    public static DBCollection getCollection () throws UnknownHostException, MongoException 
    {
        return _mongoClient.getDB(DB_NAME).getCollection(COLLECTION_NAME);
    }
    
    
    public static MongoSessionDataStoreFactory newSessionDataStoreFactory()
    {
        MongoSessionDataStoreFactory storeFactory = new MongoSessionDataStoreFactory();
        storeFactory.setHost( System.getProperty( "embedmongoHost" ) );
        storeFactory.setPort( Integer.getInteger( "embedmongoPort" ) );
        storeFactory.setCollectionName(COLLECTION_NAME);
        storeFactory.setDbName(DB_NAME);
        return storeFactory;
    }
    
    
    public static boolean checkSessionExists (String id)
    throws Exception
    {
        DBCollection collection = _mongoClient.getDB(DB_NAME).getCollection(COLLECTION_NAME);
        
        DBObject fields = new BasicDBObject();
        fields.put(MongoSessionDataStore.__EXPIRY, 1);
        fields.put(MongoSessionDataStore.__VALID, 1);
        
        DBObject sessionDocument = collection.findOne(new BasicDBObject(MongoSessionDataStore.__ID, id), fields);
        
        if (sessionDocument == null)
            return false; //doesn't exist

       return true;
    }
    
    
    public static boolean checkSessionPersisted (SessionData data)
    throws Exception
    {
        DBCollection collection = _mongoClient.getDB(DB_NAME).getCollection(COLLECTION_NAME);
        
        DBObject fields = new BasicDBObject();
        
        DBObject sessionDocument = collection.findOne(new BasicDBObject(MongoSessionDataStore.__ID, data.getId()), fields);
        if (sessionDocument == null)
            return false; //doesn't exist
        
        LOG.info("{}",sessionDocument);
        
        Boolean valid = (Boolean)sessionDocument.get(MongoSessionDataStore.__VALID);   


        if (valid == null || !valid)
            return false;

        Long created = (Long)sessionDocument.get(MongoSessionDataStore.__CREATED);
        Long accessed = (Long)sessionDocument.get(MongoSessionDataStore.__ACCESSED);
        Long lastAccessed = (Long)sessionDocument.get(MongoSessionDataStore.__LAST_ACCESSED);
        Long maxInactive = (Long)sessionDocument.get(MongoSessionDataStore.__MAX_IDLE);
        Long expiry = (Long)sessionDocument.get(MongoSessionDataStore.__EXPIRY);

        Object version = MongoUtils.getNestedValue(sessionDocument, 
                                        MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() +"."+MongoSessionDataStore.__VERSION);
        Long lastSaved = (Long)MongoUtils.getNestedValue(sessionDocument, 
                                              MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() +"."+MongoSessionDataStore.__LASTSAVED);
        String lastNode = (String)MongoUtils.getNestedValue(sessionDocument, 
                                                 MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() +"."+MongoSessionDataStore.__LASTNODE);
        

        LOG.info("DA:{} MA:{}", data.getAccessed(), accessed);
        LOG.info("DLA:{} DLA:{}",data.getLastAccessed(),lastAccessed);
        assertEquals(data.getCreated(), created.longValue());
        assertEquals(data.getAccessed(), accessed.longValue());
        assertEquals(data.getLastAccessed(), lastAccessed.longValue());
        assertEquals(data.getMaxInactiveMs(), maxInactive.longValue());
        assertEquals(data.getExpiry(), expiry.longValue());
        assertEquals(data.getLastNode(), lastNode);
        assertNotNull(version);
        assertNotNull(lastSaved);

        // get the session for the context
        DBObject sessionSubDocumentForContext = 
                (DBObject)MongoUtils.getNestedValue(sessionDocument,
                                                    MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath());

        assertNotNull(sessionSubDocumentForContext);

        Map<String,Object> attributes = new HashMap<>();
        for (String name : sessionSubDocumentForContext.keySet())
        {
            //skip special metadata attribute which is not one of the actual session attributes
            if (MongoSessionDataStore.__METADATA.equals(name) )
                continue;         
            String attr = MongoUtils.decodeName(name);
            Object value = MongoUtils.decodeValue(sessionSubDocumentForContext.get(name));
            attributes.put(attr, value);
        }
        
        //same keys
        assertTrue(data.getKeys().equals(attributes.keySet()));
        //same values
        for (String name:data.getKeys())
        {
            assertTrue(data.getAttribute(name).equals(attributes.get(name)));
        }
        
        
        return true;
    }
    
    public static void createUnreadableSession (String id, String contextPath, String vhost, 
                                      String lastNode, long created, long accessed, 
                                      long lastAccessed, long maxIdle, long expiry,
                                      Map<String,Object> attributes)
    throws Exception
    {
        DBCollection collection = _mongoClient.getDB(DB_NAME).getCollection(COLLECTION_NAME);

        // Form query for upsert
        BasicDBObject key = new BasicDBObject(MongoSessionDataStore.__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

        Object version = new Long(1);

        // New session

        upsert = true;
        sets.put(MongoSessionDataStore.__CREATED,created);
        sets.put(MongoSessionDataStore.__VALID,true);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath +"."+MongoSessionDataStore.__VERSION,version);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath +"."+MongoSessionDataStore.__LASTSAVED, System.currentTimeMillis());
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath +"."+MongoSessionDataStore.__LASTNODE, lastNode);
       
        //Leaving out __MAX_IDLE to make it an invalid session object!
        
        sets.put(MongoSessionDataStore.__EXPIRY, expiry);
        sets.put(MongoSessionDataStore.__ACCESSED, accessed);
        sets.put(MongoSessionDataStore.__LAST_ACCESSED, lastAccessed);

        if (attributes != null)
        {
            for (String name : attributes.keySet())
            {
                Object value = attributes.get(name);
                sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath+ "." + MongoUtils.encodeName(name),
                         MongoUtils.encodeName(value));
            }
        }
        update.put("$set",sets);
        collection.update(key,update,upsert,false,WriteConcern.SAFE); 
    }
    

    public static void createSession (String id, String contextPath, String vhost, 
                                      String lastNode, long created, long accessed, 
                                      long lastAccessed, long maxIdle, long expiry,
                                      Map<String,Object> attributes)
    throws Exception
    {

        DBCollection collection = _mongoClient.getDB(DB_NAME).getCollection(COLLECTION_NAME);

        // Form query for upsert
        BasicDBObject key = new BasicDBObject(MongoSessionDataStore.__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

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
                sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath+ "." + MongoUtils.encodeName(name),
                         MongoUtils.encodeName(value));
            }
        }
        update.put("$set",sets);
        collection.update(key,update,upsert,false,WriteConcern.SAFE);
    }
    
    
  
    
    
}

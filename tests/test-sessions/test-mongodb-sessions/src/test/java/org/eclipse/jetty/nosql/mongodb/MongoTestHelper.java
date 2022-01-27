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

package org.eclipse.jetty.nosql.mongodb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.UnknownHostException;
import java.util.Map;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoTestHelper
 */
public class MongoTestHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(MongoTestHelper.class);
    private static final Logger MONGO_LOG = LoggerFactory.getLogger("org.eclipse.jetty.nosql.mongodb.MongoLogs");

    public static final String DB_NAME = "HttpSessions";
    public static final String COLLECTION_NAME = "testsessions";
    
    private static final int MONGO_PORT = 27017;

    static MongoDBContainer mongo =
            new MongoDBContainer(DockerImageName.parse("mongo:" + System.getProperty("mongo.docker.version", "3.2.20")))
            .withLogConsumer(new Slf4jLogConsumer(MONGO_LOG));

    static MongoClient mongoClient;

    static String mongoHost;
    static int mongoPort;

    public static MongoClient getMongoClient() throws UnknownHostException
    {
        if (mongoClient == null)
        {
            mongoClient = new MongoClient(mongoHost, mongoPort);
        }
        return mongoClient;
    }

    public static void dropCollection() throws Exception
    {
        getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME).drop();
    }

    public static void shutdown() throws Exception
    {
        mongo.stop();
    }

    public static void createCollection() throws UnknownHostException, MongoException
    {
        getMongoClient().getDB(DB_NAME).createCollection(COLLECTION_NAME, null);
    }

    public static DBCollection getCollection() throws UnknownHostException, MongoException
    {
        return getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME);
    }

    public static MongoSessionDataStoreFactory newSessionDataStoreFactory()
    {
        if (!mongo.isRunning())
        {
            try
            {
                long start = System.currentTimeMillis();
                mongo.start();
                mongoHost = mongo.getHost();
                mongoPort = mongo.getMappedPort(MONGO_PORT);
                LOG.info("Mongo container started for {}:{} - {}ms", mongoHost, mongoPort,
                        System.currentTimeMillis() - start);
                mongoClient = new MongoClient(mongoHost, mongoPort);
            }
            catch (Exception e)
            {
                LOG.error(e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }

        MongoSessionDataStoreFactory storeFactory = new MongoSessionDataStoreFactory();
        storeFactory.setHost(mongoHost);
        storeFactory.setPort(mongoPort);
        storeFactory.setCollectionName(COLLECTION_NAME);
        storeFactory.setDbName(DB_NAME);
        return storeFactory;
    }

    public static boolean checkSessionExists(String id)
        throws Exception
    {
        DBCollection collection = getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME);

        DBObject fields = new BasicDBObject();
        fields.put(MongoSessionDataStore.__EXPIRY, 1);
        fields.put(MongoSessionDataStore.__VALID, 1);

        DBObject sessionDocument = collection.findOne(new BasicDBObject(MongoSessionDataStore.__ID, id), fields);

        if (sessionDocument == null)
            return false; //doesn't exist

        return true;
    }

    public static boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        DBCollection collection = getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME);

        DBObject fields = new BasicDBObject();

        DBObject sessionDocument = collection.findOne(new BasicDBObject(MongoSessionDataStore.__ID, data.getId()), fields);
        if (sessionDocument == null)
            return false; //doesn't exist

        LOG.debug("{}", sessionDocument);

        Boolean valid = (Boolean)sessionDocument.get(MongoSessionDataStore.__VALID);

        if (valid == null || !valid)
            return false;

        Long created = (Long)sessionDocument.get(MongoSessionDataStore.__CREATED);
        Long accessed = (Long)sessionDocument.get(MongoSessionDataStore.__ACCESSED);
        Long lastAccessed = (Long)sessionDocument.get(MongoSessionDataStore.__LAST_ACCESSED);
        Long maxInactive = (Long)sessionDocument.get(MongoSessionDataStore.__MAX_IDLE);
        Long expiry = (Long)sessionDocument.get(MongoSessionDataStore.__EXPIRY);

        Object version = MongoUtils.getNestedValue(sessionDocument,
            MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() + "." + MongoSessionDataStore.__VERSION);
        Long lastSaved = (Long)MongoUtils.getNestedValue(sessionDocument,
            MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() + "." + MongoSessionDataStore.__LASTSAVED);
        String lastNode = (String)MongoUtils.getNestedValue(sessionDocument,
            MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() + "." + MongoSessionDataStore.__LASTNODE);
        byte[] attributes = (byte[])MongoUtils.getNestedValue(sessionDocument,
            MongoSessionDataStore.__CONTEXT + "." + data.getVhost().replace('.', '_') + ":" + data.getContextPath() + "." + MongoSessionDataStore.__ATTRIBUTES);

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

        if (!data.getAllAttributes().isEmpty())
        {
            assertNotNull(attributes);
            SessionData tmp = new SessionData(data.getId(), data.getContextPath(), data.getVhost(), created.longValue(), accessed.longValue(), lastAccessed.longValue(), maxInactive.longValue());
            try (ByteArrayInputStream bais = new ByteArrayInputStream(attributes);
                 ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(bais))
            {
                SessionData.deserializeAttributes(tmp, ois);
            }

            //same keys
            assertTrue(data.getKeys().equals(tmp.getKeys()));
            //same values
            for (String name : data.getKeys())
            {
                assertTrue(data.getAttribute(name).equals(tmp.getAttribute(name)));
            }
        }

        return true;
    }

    public static void createUnreadableSession(String id, String contextPath, String vhost,
                                               String lastNode, long created, long accessed,
                                               long lastAccessed, long maxIdle, long expiry,
                                               Map<String, Object> attributes)
        throws Exception
    {
        DBCollection collection = getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME);

        // Form query for upsert
        BasicDBObject key = new BasicDBObject(MongoSessionDataStore.__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

        Object version = 1L;

        // New session

        upsert = true;
        sets.put(MongoSessionDataStore.__CREATED, created);
        sets.put(MongoSessionDataStore.__VALID, true);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__VERSION, version);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__LASTSAVED, System.currentTimeMillis());
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__LASTNODE, lastNode);

        //Leaving out __MAX_IDLE to make it an invalid session object!

        sets.put(MongoSessionDataStore.__EXPIRY, expiry);
        sets.put(MongoSessionDataStore.__ACCESSED, accessed);
        sets.put(MongoSessionDataStore.__LAST_ACCESSED, lastAccessed);

        if (attributes != null)
        {
            SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle, attributes);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos))
            {
                SessionData.serializeAttributes(tmp, oos);
                sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__ATTRIBUTES, baos.toByteArray());
            }
        }

        update.put("$set", sets);
        collection.update(key, update, upsert, false, WriteConcern.SAFE);
    }

    public static void createSession(String id, String contextPath, String vhost,
                                     String lastNode, long created, long accessed,
                                     long lastAccessed, long maxIdle, long expiry,
                                     Map<String, Object> attributes)
        throws Exception
    {

        DBCollection collection = getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME);

        // Form query for upsert
        BasicDBObject key = new BasicDBObject(MongoSessionDataStore.__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

        Object version = 1L;

        // New session
        upsert = true;
        sets.put(MongoSessionDataStore.__CREATED, created);
        sets.put(MongoSessionDataStore.__VALID, true);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__VERSION, version);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__LASTSAVED, System.currentTimeMillis());
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__LASTNODE, lastNode);
        sets.put(MongoSessionDataStore.__MAX_IDLE, maxIdle);
        sets.put(MongoSessionDataStore.__EXPIRY, expiry);
        sets.put(MongoSessionDataStore.__ACCESSED, accessed);
        sets.put(MongoSessionDataStore.__LAST_ACCESSED, lastAccessed);

        if (attributes != null)
        {
            SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle, attributes);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos);)
            {
                SessionData.serializeAttributes(tmp, oos);
                sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__ATTRIBUTES, baos.toByteArray());
            }
        }

        update.put("$set", sets);
        collection.update(key, update, upsert, false, WriteConcern.SAFE);
    }

    public static void createLegacySession(String id, String contextPath, String vhost,
                                           String lastNode, long created, long accessed,
                                           long lastAccessed, long maxIdle, long expiry,
                                           Map<String, Object> attributes)
        throws Exception
    {
        //make old-style session to test if we can retrieve it
        DBCollection collection = getMongoClient().getDB(DB_NAME).getCollection(COLLECTION_NAME);

        // Form query for upsert
        BasicDBObject key = new BasicDBObject(MongoSessionDataStore.__ID, id);

        // Form updates
        BasicDBObject update = new BasicDBObject();
        boolean upsert = false;
        BasicDBObject sets = new BasicDBObject();

        Object version = 1L;

        // New session
        upsert = true;
        sets.put(MongoSessionDataStore.__CREATED, created);
        sets.put(MongoSessionDataStore.__VALID, true);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__VERSION, version);
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__LASTSAVED, System.currentTimeMillis());
        sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoSessionDataStore.__LASTNODE, lastNode);
        sets.put(MongoSessionDataStore.__MAX_IDLE, maxIdle);
        sets.put(MongoSessionDataStore.__EXPIRY, expiry);
        sets.put(MongoSessionDataStore.__ACCESSED, accessed);
        sets.put(MongoSessionDataStore.__LAST_ACCESSED, lastAccessed);

        if (attributes != null)
        {
            for (String name : attributes.keySet())
            {
                Object value = attributes.get(name);
                sets.put(MongoSessionDataStore.__CONTEXT + "." + vhost.replace('.', '_') + ":" + contextPath + "." + MongoUtils.encodeName(name),
                    MongoUtils.encodeName(value));
            }
        }
        update.put("$set", sets);
        collection.update(key, update, upsert, false, WriteConcern.SAFE);
    }
}

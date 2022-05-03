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

package org.eclipse.jetty.gcloud.session;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.datastore.Batch;
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
import org.eclipse.jetty.gcloud.session.GCloudSessionDataStore.EntityDataModel;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionHandler;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.DatastoreEmulatorContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GCloudSessionTestSupport
 */
public class GCloudSessionTestSupport
{
    Datastore _ds;
    KeyFactory _keyFactory;

    private static final Logger LOGGER = LoggerFactory.getLogger(GCloudSessionTestSupport.class);
    private static final Logger GCLOUD_LOG = LoggerFactory.getLogger("org.eclipse.jetty.gcloud.session.gcloudLogs");

    public DatastoreEmulatorContainer emulator = new CustomDatastoreEmulatorContainer(
        DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk:316.0.0-emulators")
    ).withLogConsumer(new Slf4jLogConsumer(GCLOUD_LOG));

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk");

    private static final String CMD = "gcloud beta emulators datastore start --project test-project --host-port 0.0.0.0:8081 --consistency=1.0";
    private static final int HTTP_PORT = 8081;

    public static class CustomDatastoreEmulatorContainer extends DatastoreEmulatorContainer
    {
        public CustomDatastoreEmulatorContainer(DockerImageName dockerImageName)
        {
            super(dockerImageName);

            dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);

            withExposedPorts(HTTP_PORT);
            setWaitStrategy(Wait.forHttp("/").forStatusCode(200));
            withCommand("/bin/sh", "-c", CMD);
        }
    }

    public static class TestGCloudSessionDataStoreFactory extends GCloudSessionDataStoreFactory
    {
        Datastore _d;

        public TestGCloudSessionDataStoreFactory(Datastore d)
        {
            _d = d;
        }

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

    public GCloudSessionTestSupport()
    {
        // no op
    }

    public void setUp()
        throws Exception
    {
        emulator.start();
        String host;
        //work out if we're running locally or not: if not local, then the host passed to
        //DatastoreOptions must be prefixed with a scheme
        String endPoint = emulator.getEmulatorEndpoint();
        InetAddress hostAddr = InetAddress.getByName(new URL("http://" + endPoint).getHost());
        LOGGER.info("endPoint: {} ,hostAddr.isAnyLocalAddress(): {},hostAddr.isLoopbackAddress(): {}",
                    endPoint,
                    hostAddr.isAnyLocalAddress(),
                    hostAddr.isLoopbackAddress());
        if (hostAddr.isAnyLocalAddress() || hostAddr.isLoopbackAddress())
            host = endPoint;
        else
            host = "http://" + endPoint;
        
        DatastoreOptions options = DatastoreOptions.newBuilder()
            .setHost(host)
            .setCredentials(NoCredentials.getInstance())
            .setRetrySettings(ServiceOptions.getNoRetrySettings())
            .setProjectId("test-project")
            .build();
        _ds = options.getService();
        _keyFactory = _ds.newKeyFactory().setKind(EntityDataModel.KIND);
    }

    public Datastore getDatastore()
    {
        return _ds;
    }

    public void tearDown()
        throws Exception
    {
        emulator.stop();
    }

    public void reset() throws Exception
    {
        emulator.stop();
        this.setUp();
    }

    public void createSession(String id, String contextPath, String vhost,
                              String lastNode, long created, long accessed,
                              long lastAccessed, long maxIdle, long expiry,
                              long cookieset, long lastSaved,
                              Map<String, Object> attributes)
        throws Exception
    {
        //serialize the attribute map
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            if (attributes != null)
            {
                SessionData tmp = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxIdle);
                ObjectOutputStream oos = new ObjectOutputStream(baos);
                SessionData.serializeAttributes(tmp, oos);
            }

            //turn a session into an entity         
            Entity.Builder builder = Entity.newBuilder(_keyFactory.newKey(contextPath + "_" + vhost + "_" + id))
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
    }

    public boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        Entity entity = _ds.get(_keyFactory.newKey(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()));
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
        Blob blob = (Blob)entity.getBlob(EntityDataModel.ATTRIBUTES);

        SessionData tmp = new SessionData(data.getId(), entity.getString(EntityDataModel.CONTEXTPATH),
            entity.getString(EntityDataModel.VHOST),
            entity.getLong(EntityDataModel.CREATETIME),
            entity.getLong(EntityDataModel.ACCESSED),
            entity.getLong(EntityDataModel.LASTACCESSED),
            entity.getLong(EntityDataModel.MAXINACTIVE));

        try (ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(blob.asInputStream()))
        {
            SessionData.deserializeAttributes(tmp, ois);
        }

        //same number of attributes
        assertEquals(data.getAllAttributes().size(), tmp.getAllAttributes().size());
        //same keys
        assertTrue(data.getKeys().equals(tmp.getAllAttributes().keySet()));
        //same values
        for (String name : data.getKeys())
        {
            assertTrue(data.getAttribute(name).equals(tmp.getAttribute(name)));
        }

        return true;
    }

    public boolean checkSessionExists(String id)
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

    public Set<String> getSessionIds() throws Exception
    {
        HashSet<String> ids = new HashSet<String>();
        GqlQuery.Builder builder = Query.newGqlQueryBuilder(ResultType.ENTITY, "select * from " + GCloudSessionDataStore.EntityDataModel.KIND);

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

    public void listSessions() throws Exception
    {

        GqlQuery.Builder builder = Query.newGqlQueryBuilder(ResultType.ENTITY, "select * from " + GCloudSessionDataStore.EntityDataModel.KIND);

        Query<Entity> query = builder.build();

        QueryResults<Entity> results = _ds.run(query);
        assertNotNull(results);
        System.err.println("SESSIONS::::::::");
        while (results.hasNext())
        {

            Entity e = results.next();
            System.err.println(e.getString("clusterId") + " expires at " + e.getLong("expiry"));
        }
        System.err.println("END OF SESSIONS::::::::");
    }

    public void assertSessions(int count) throws Exception
    {
        Query<Key> query = Query.newKeyQueryBuilder().setKind(GCloudSessionDataStore.EntityDataModel.KIND).build();
        QueryResults<Key> results = _ds.run(query);
        assertNotNull(results);
        int actual = 0;
        List<Key> keys = new ArrayList<>();
        while (results.hasNext())
        {
            Key key = results.next();
            keys.add(key);
            ++actual;
        }
        assertEquals(count, actual, "keys found: " + keys);
    }

    public void deleteSessions() throws Exception
    {
        Query<Key> query = Query.newKeyQueryBuilder().setKind(GCloudSessionDataStore.EntityDataModel.KIND).build();
        QueryResults<Key> results = _ds.run(query);

        Batch batch = _ds.newBatch();

        if (results != null)
        {
            List<Key> keys = new ArrayList<>();

            while (results.hasNext())
            {
                keys.add(results.next());
            }

            batch.delete(keys.toArray(new Key[keys.size()]));
        }

        batch.submit();
        assertSessions(0);
    }
}

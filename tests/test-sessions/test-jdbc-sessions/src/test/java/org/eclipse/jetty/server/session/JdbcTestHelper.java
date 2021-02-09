//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JdbcTestHelper
 */
public class JdbcTestHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(JdbcTestHelper.class);
    private static final Logger MARIADB_LOG = LoggerFactory.getLogger("org.eclipse.jetty.server.session.MariaDbLogs");

    public static String DRIVER_CLASS;
    public static String DEFAULT_CONNECTION_URL;

    public static final int STALE_INTERVAL = 1;

    public static final String EXPIRY_COL = "extime";
    public static final String LAST_ACCESS_COL = "latime";
    public static final String LAST_NODE_COL = "lnode";
    public static final String LAST_SAVE_COL = "lstime";
    public static final String MAP_COL = "mo";
    public static final String MAX_IDLE_COL = "mi";
    public static final String TABLE = "mysessions";
    public static final String ID_COL = "mysessionid";
    public static final String ACCESS_COL = "atime";
    public static final String CONTEXT_COL = "cpath";
    public static final String COOKIE_COL = "cooktime";
    public static final String CREATE_COL = "ctime";

    static MariaDBContainer MARIAD_DB;

    static final String MARIA_DB_USER = "beer";
    static final String MARIA_DB_PASSWORD = "pacific_ale";

    static
    {
        try
        {
            long start = System.currentTimeMillis();
            MARIAD_DB =
                new MariaDBContainer("mariadb:" + System.getProperty("mariadb.docker.version", "10.3.6"))
                    .withUsername(MARIA_DB_USER)
                    .withPassword(MARIA_DB_PASSWORD)
                    .withDatabaseName("sessions");
            MARIAD_DB.withLogConsumer(new Slf4jLogConsumer(MARIADB_LOG)).start();
            String containerIpAddress =  MARIAD_DB.getContainerIpAddress();
            int mariadbPort = MARIAD_DB.getMappedPort(3306);
            DEFAULT_CONNECTION_URL = MARIAD_DB.getJdbcUrl();
            DRIVER_CLASS = MARIAD_DB.getDriverClassName();
            LOG.info("Mariadb container started for {}:{} - {}ms", containerIpAddress, mariadbPort,
                     System.currentTimeMillis() - start);
            DEFAULT_CONNECTION_URL = DEFAULT_CONNECTION_URL + "?user=" + MARIA_DB_USER +
                "&password=" + MARIA_DB_PASSWORD;
            LOG.info("DEFAULT_CONNECTION_URL: {}", DEFAULT_CONNECTION_URL);
        }
        catch (Exception e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static void shutdown(String connectionUrl)
        throws Exception
    {
        try (Connection connection = getConnection())
        {
            connection.prepareStatement("truncate table " + TABLE).executeUpdate();
        }
    }

    public static DatabaseAdaptor buildDatabaseAdaptor()
    {
        DatabaseAdaptor da = new DatabaseAdaptor();
        da.setDriverInfo(DRIVER_CLASS, DEFAULT_CONNECTION_URL);
        return da;
    }

    public static Connection getConnection()
        throws Exception
    {
        Class.forName(DRIVER_CLASS);
        return DriverManager.getConnection(DEFAULT_CONNECTION_URL);
    }

    /**
     * @return a fresh JDBCSessionDataStoreFactory
     */
    public static SessionDataStoreFactory newSessionDataStoreFactory()
    {
        return newSessionDataStoreFactory(buildDatabaseAdaptor());
    }

    public static SessionDataStoreFactory newSessionDataStoreFactory(DatabaseAdaptor da)
    {
        JDBCSessionDataStoreFactory factory = new JDBCSessionDataStoreFactory();
        factory.setDatabaseAdaptor(da);
        JDBCSessionDataStore.SessionTableSchema sessionTableSchema = newSessionTableSchema();
        factory.setSessionTableSchema(sessionTableSchema);
        return factory;
    }

    public static JDBCSessionDataStore.SessionTableSchema newSessionTableSchema()
    {
        JDBCSessionDataStore.SessionTableSchema sessionTableSchema = new JDBCSessionDataStore.SessionTableSchema();
        sessionTableSchema.setTableName(TABLE);
        sessionTableSchema.setIdColumn(ID_COL);
        sessionTableSchema.setAccessTimeColumn(ACCESS_COL);
        sessionTableSchema.setContextPathColumn(CONTEXT_COL);
        sessionTableSchema.setCookieTimeColumn(COOKIE_COL);
        sessionTableSchema.setCreateTimeColumn(CREATE_COL);
        sessionTableSchema.setExpiryTimeColumn(EXPIRY_COL);
        sessionTableSchema.setLastAccessTimeColumn(LAST_ACCESS_COL);
        sessionTableSchema.setLastNodeColumn(LAST_NODE_COL);
        sessionTableSchema.setLastSavedTimeColumn(LAST_SAVE_COL);
        sessionTableSchema.setMapColumn(MAP_COL);
        sessionTableSchema.setMaxIntervalColumn(MAX_IDLE_COL);
        return sessionTableSchema;
    }

    public static void prepareTables() throws SQLException
    {
        DatabaseAdaptor da = buildDatabaseAdaptor();
        JDBCSessionDataStore.SessionTableSchema sessionTableSchema = newSessionTableSchema();
        sessionTableSchema.setDatabaseAdaptor(da);

        sessionTableSchema.prepareTables();
    }
    
    public static void dumpRow(ResultSet row) throws SQLException
    {
        if (row != null)
        {
            String id = row.getString(ID_COL);
            long created = row.getLong(CREATE_COL);
            long accessed = row.getLong(ACCESS_COL);
            long lastAccessed = row.getLong(LAST_ACCESS_COL);
            long maxIdle = row.getLong(MAX_IDLE_COL);
            long cookieSet = row.getLong(COOKIE_COL);
            String node = row.getString(LAST_NODE_COL);
            long expires = row.getLong(EXPIRY_COL);
            long lastSaved = row.getLong(LAST_SAVE_COL);
            String context = row.getString(CONTEXT_COL);
            Blob blob = row.getBlob(MAP_COL);
            
            String dump = "id=" + id +
                          " ctxt=" + context +
                          " node=" + node +
                          " exp=" + expires +
                          " acc=" + accessed +
                          " lacc=" + lastAccessed +
                          " ck=" + cookieSet +
                          " lsv=" + lastSaved +
                          " blob length=" + blob.length();
            System.err.println(dump);
        }
    }

    public static boolean existsInSessionTable(String id, boolean verbose)
        throws Exception
    {
        try (Connection con = getConnection())
        {
            PreparedStatement statement = con.prepareStatement("select * from " +
                TABLE +
                " where " + ID_COL + " = ?");
            statement.setString(1, id);
            ResultSet result = statement.executeQuery();
            if (verbose)
            {
                boolean results = false;
                while (result.next())
                {
                    results = true;
                    dumpRow(result);
                }
                return results;
            }
            else
                return result.next();
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean checkSessionPersisted(SessionData data)
        throws Exception
    {
        PreparedStatement statement = null;
        ResultSet result = null;
        try (Connection con = getConnection())
        {
            statement = con.prepareStatement("select * from " + TABLE +
                    " where " + ID_COL + " = ? and " + CONTEXT_COL +
                " = ? and virtualHost = ?");
            statement.setString(1, data.getId());
            statement.setString(2, data.getContextPath());
            statement.setString(3, data.getVhost());

            result = statement.executeQuery();

            if (!result.next())
                return false;

            assertEquals(data.getCreated(), result.getLong(CREATE_COL));
            assertEquals(data.getAccessed(), result.getLong(ACCESS_COL));
            assertEquals(data.getLastAccessed(), result.getLong(LAST_ACCESS_COL));
            assertEquals(data.getMaxInactiveMs(), result.getLong(MAX_IDLE_COL));

            assertEquals(data.getCookieSet(), result.getLong(COOKIE_COL));
            assertEquals(data.getLastNode(), result.getString(LAST_NODE_COL));

            assertEquals(data.getExpiry(), result.getLong(EXPIRY_COL));
            assertEquals(data.getContextPath(), result.getString(CONTEXT_COL));
            assertEquals(data.getVhost(), result.getString("virtualHost"));

            Blob blob = result.getBlob(MAP_COL);

            SessionData tmp =
                new SessionData(data.getId(), data.getContextPath(), data.getVhost(), result.getLong(CREATE_COL),
                    result.getLong(ACCESS_COL), result.getLong(LAST_ACCESS_COL),
                    result.getLong(MAX_IDLE_COL));

            if (blob.length() > 0)
            {
                try (InputStream is = blob.getBinaryStream();
                     ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(is))
                {
                    SessionData.deserializeAttributes(tmp, ois);
                }
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
        }
        finally
        {
            if (result != null)
                result.close();
            if (statement != null)
                statement.close();
        }

        return true;
    }
    
    public static void insertSession(SessionData data) throws Exception
    {
        try (Connection con = getConnection())
        {
            PreparedStatement statement = con.prepareStatement("insert into " + TABLE +
                " (" + ID_COL + ", " + CONTEXT_COL + ", virtualHost, " + LAST_NODE_COL +
                ", " + ACCESS_COL + ", " + LAST_ACCESS_COL + ", " + CREATE_COL + ", " + COOKIE_COL +
                ", " + LAST_SAVE_COL + ", " + EXPIRY_COL + ", " + MAX_IDLE_COL + "," + MAP_COL + " ) " +
                " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            statement.setString(1, data.getId());
            statement.setString(2, data.getContextPath());
            statement.setString(3, data.getVhost());
            statement.setString(4, data.getLastNode());

            statement.setLong(5, data.getAccessed());
            statement.setLong(6, data.getLastAccessed());
            statement.setLong(7, data.getCreated());
            statement.setLong(8, data.getCookieSet());

            statement.setLong(9, data.getLastSaved());
            statement.setLong(10, data.getExpiry());
            statement.setLong(11, data.getMaxInactiveMs());
            
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos);)
            {
                SessionData.serializeAttributes(data, oos);
                byte[] bytes = baos.toByteArray();

                try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);)
                {
                    statement.setBinaryStream(12, bais, bytes.length);
                }
            }
            statement.execute();
            assertEquals(1, statement.getUpdateCount());
        }
    }

    public static void insertUnreadableSession(String id, String contextPath, String vhost,
                                     String lastNode, long created, long accessed,
                                     long lastAccessed, long maxIdle, long expiry,
                                     long cookieSet, long lastSaved)
        throws Exception
    {
        try (Connection con = getConnection())
        {
            PreparedStatement statement = con.prepareStatement("insert into " + TABLE +
                " (" + ID_COL + ", " + CONTEXT_COL + ", virtualHost, " + LAST_NODE_COL +
                ", " + ACCESS_COL + ", " + LAST_ACCESS_COL + ", " + CREATE_COL + ", " + COOKIE_COL +
                ", " + LAST_SAVE_COL + ", " + EXPIRY_COL + ", " + MAX_IDLE_COL + "," + MAP_COL + " ) " +
                " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            statement.setString(1, id);
            statement.setString(2, contextPath);
            statement.setString(3, vhost);
            statement.setString(4, lastNode);

            statement.setLong(5, accessed);
            statement.setLong(6, lastAccessed);
            statement.setLong(7, created);
            statement.setLong(8, cookieSet);

            statement.setLong(9, lastSaved);
            statement.setLong(10, expiry);
            statement.setLong(11, maxIdle);

            statement.setBinaryStream(12, new ByteArrayInputStream("".getBytes()), 0);

            statement.execute();
            assertEquals(1, statement.getUpdateCount());
        }
    }

    public static Set<String> getSessionIds()
        throws Exception
    {
        HashSet<String> ids = new HashSet<>();
        try (Connection con = getConnection())
        {
            PreparedStatement statement = con.prepareStatement("select " + ID_COL + " from " + TABLE);
            ResultSet result = statement.executeQuery();
            while (result.next())
            {
                ids.add(result.getString(1));
            }
            return ids;
        }
    }
}

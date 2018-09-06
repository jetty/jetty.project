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

package org.eclipse.jetty.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;

/**
 * JdbcTestHelper
 */
public class JdbcTestHelper
{
    public static final String DRIVER_CLASS = "org.apache.derby.jdbc.EmbeddedDriver";
    public static final String DEFAULT_CONNECTION_URL = "jdbc:derby:memory:sessions;create=true";
    public static final String DEFAULT_SHUTDOWN_URL = "jdbc:derby:memory:sessions;drop=true";
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
    
    static 
    {
        System.setProperty("derby.system.home", MavenTestingUtils.getTargetFile("test-derby").getAbsolutePath());
    }
    
    
    public static void shutdown (String connectionUrl)
    throws Exception
    {
        if (connectionUrl == null)
            connectionUrl = DEFAULT_SHUTDOWN_URL;
        
        try
        {
            DriverManager.getConnection(connectionUrl);
        }
        catch( SQLException expected )
        {
            if (!"08006".equals(expected.getSQLState()))
            {
               throw expected;
            }
        }
    }

  
 
   
  
    /**
     * @return a fresh JDBCSessionDataStoreFactory
     */
    public static SessionDataStoreFactory newSessionDataStoreFactory()
    {
        DatabaseAdaptor da = new DatabaseAdaptor();
        da.setDriverInfo(DRIVER_CLASS, DEFAULT_CONNECTION_URL);
        return newSessionDataStoreFactory(da);
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
   
   
   public static void prepareTables () throws SQLException
   {
       DatabaseAdaptor da = new DatabaseAdaptor();
       da.setDriverInfo(DRIVER_CLASS, DEFAULT_CONNECTION_URL);
       JDBCSessionDataStore.SessionTableSchema sessionTableSchema = newSessionTableSchema();
       sessionTableSchema.setDatabaseAdaptor(da);
       
       sessionTableSchema.prepareTables();
       
   }
    
    public static boolean existsInSessionTable(String id, boolean verbose)
    throws Exception
    {
        Class.forName(DRIVER_CLASS);
        Connection con = null;
        try
        {
            con = DriverManager.getConnection(DEFAULT_CONNECTION_URL);
            PreparedStatement statement = con.prepareStatement("select * from "+
                    TABLE+
                    " where "+ID_COL+" = ?");
            statement.setString(1, id);
            ResultSet result = statement.executeQuery();
            if (verbose)
            {
                boolean results = false;
                while (result.next())
                {
                    results = true;
                }
                return results;
            }
            else
                return result.next();
        }
        finally
        {
            if (con != null)
                con.close();
        }
    }
    
    
    @SuppressWarnings("unchecked")
    public static boolean checkSessionPersisted (SessionData data)
            throws Exception
    {
        Class.forName(DRIVER_CLASS);
        PreparedStatement statement = null;
        ResultSet result = null;
        try (Connection con=DriverManager.getConnection(DEFAULT_CONNECTION_URL);)
        {
            statement = con.prepareStatement("select * from "+TABLE+
                                             " where "+ID_COL+" = ? and "+CONTEXT_COL+
                    " = ? and virtualHost = ?");
            statement.setString(1, data.getId());
            statement.setString(2, data.getContextPath());
            statement.setString(3, data.getVhost());
            
            result = statement.executeQuery();

            if (!result.next())
                return false;


            assertEquals(data.getCreated(),result.getLong(CREATE_COL));
            assertEquals(data.getAccessed(), result.getLong(ACCESS_COL));
            assertEquals(data.getLastAccessed(), result.getLong(LAST_ACCESS_COL)); 
            assertEquals(data.getMaxInactiveMs(), result.getLong(MAX_IDLE_COL));

            assertEquals(data.getCookieSet(), result.getLong(COOKIE_COL));
            assertEquals(data.getLastNode(), result.getString(LAST_NODE_COL));

            assertEquals(data.getExpiry(), result.getLong(EXPIRY_COL));
            assertEquals(data.getContextPath(), result.getString(CONTEXT_COL));          
            assertEquals(data.getVhost(), result.getString("virtualHost"));

            Map<String,Object> attributes = new HashMap<>();
            Blob blob = result.getBlob(MAP_COL);

            try (InputStream is = blob.getBinaryStream();
                 ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(is))
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

    public static void insertSession (String id, String contextPath, String vhost)
    throws Exception
    {
        Class.forName(DRIVER_CLASS);
        try (Connection con=DriverManager.getConnection(DEFAULT_CONNECTION_URL);)
        {
            PreparedStatement statement = con.prepareStatement("insert into "+TABLE+
                                                               " ("+ID_COL+", "+CONTEXT_COL+", virtualHost, "+LAST_NODE_COL+
                                                               ", "+ACCESS_COL+", "+LAST_ACCESS_COL+", "+CREATE_COL+", "+COOKIE_COL+
                                                               ", "+LAST_SAVE_COL+", "+EXPIRY_COL+" "+") "+
                                                               " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
           
            statement.setString(1, id);
            statement.setString(2, contextPath);
            statement.setString(3,  vhost);
            statement.setString(4, "0");
            
            statement.setLong(5, System.currentTimeMillis());
            statement.setLong(6, System.currentTimeMillis());
            statement.setLong(7, System.currentTimeMillis());
            statement.setLong(8, System.currentTimeMillis());
            
            statement.setLong(9, System.currentTimeMillis());
            statement.setLong(10, System.currentTimeMillis());

            statement.execute();
            assertEquals(1,statement.getUpdateCount());
        }
    }
    
    
    public static void insertSession (String id, String contextPath, String vhost, 
                                      String lastNode, long created, long accessed, 
                                      long lastAccessed, long maxIdle, long expiry,
                                      long cookieSet, long lastSaved, Map<String,Object> attributes)
    throws Exception
    {
        Class.forName(DRIVER_CLASS);
        try (Connection con=DriverManager.getConnection(DEFAULT_CONNECTION_URL);)
        {
            PreparedStatement statement = con.prepareStatement("insert into "+TABLE+
                                                               " ("+ID_COL+", "+CONTEXT_COL+", virtualHost, "+LAST_NODE_COL+
                                                               ", "+ACCESS_COL+", "+LAST_ACCESS_COL+", "+CREATE_COL+", "+COOKIE_COL+
                                                               ", "+LAST_SAVE_COL+", "+EXPIRY_COL+", "+MAX_IDLE_COL+","+MAP_COL+" ) "+
                                                               " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
           
            statement.setString(1, id);
            statement.setString(2, contextPath);
            statement.setString(3,  vhost);
            statement.setString(4, lastNode);
            
            statement.setLong(5, accessed);
            statement.setLong(6, lastAccessed);
            statement.setLong(7, created);
            statement.setLong(8, cookieSet);

            statement.setLong(9, lastSaved);
            statement.setLong(10, expiry);
            statement.setLong(11, maxIdle);
            
            if (attributes != null)
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos);
                Map<String,Object> emptyMap = Collections.emptyMap();
                oos.writeObject(emptyMap);
                oos.flush();
                byte[] bytes = baos.toByteArray();

                ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                statement.setBinaryStream(12, bais, bytes.length);//attribute map as blob
            }
            else
                statement.setBinaryStream(12, new ByteArrayInputStream("".getBytes()), 0);
            
            statement.execute();
            assertEquals(1,statement.getUpdateCount());
        }     
    }
    
    

    
    public static Set<String> getSessionIds ()
    throws Exception
    {
        HashSet<String> ids = new HashSet<String>();
        Class.forName(DRIVER_CLASS);
        Connection con = null;
        try
        {
            con = DriverManager.getConnection(DEFAULT_CONNECTION_URL);
            PreparedStatement statement = con.prepareStatement("select "+ID_COL+" from "+TABLE);      
            ResultSet result = statement.executeQuery();
            while (result.next())
            {
                ids.add(result.getString(1));
            }
            return ids;
        }
        finally
        {
            if (con != null)
                con.close();
        }
    }
}

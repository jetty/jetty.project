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

package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

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

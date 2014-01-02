//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

/**
 * JdbcTestServer
 */
public class JdbcTestServer extends AbstractTestServer
{
    public static final String DRIVER_CLASS = "org.apache.derby.jdbc.EmbeddedDriver";
    public static final String DEFAULT_CONNECTION_URL = "jdbc:derby:sessions;create=true";
    public static final int SAVE_INTERVAL = 1;
    
    
    static 
    {
        System.setProperty("derby.system.home", MavenTestingUtils.getTargetFile("test-derby").getAbsolutePath());
    }

    
    public JdbcTestServer(int port)
    {
        super(port);
    }
    
    public JdbcTestServer(int port, int maxInactivePeriod, int scavengePeriod, String connectionUrl)
    {
        super(port, maxInactivePeriod, scavengePeriod, connectionUrl);
    }
    
    public JdbcTestServer(int port, int maxInactivePeriod, int scavengePeriod)
    {
        super(port, maxInactivePeriod, scavengePeriod, DEFAULT_CONNECTION_URL);
    }
    
 
    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionHandler(org.eclipse.jetty.server.SessionManager)
     */
    @Override
    public SessionHandler newSessionHandler(SessionManager sessionManager)
    {
        return new SessionHandler(sessionManager);
    }

    static int __workers=0;
    
    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionIdManager(String)
     */
    @Override
    public  SessionIdManager newSessionIdManager(String config)
    {
        synchronized(JdbcTestServer.class)
        {
            JDBCSessionIdManager idManager = new JDBCSessionIdManager(_server);
            idManager.setScavengeInterval(_scavengePeriod);
            idManager.setWorkerName("w"+(__workers++));
            idManager.setDriverInfo(DRIVER_CLASS, (config==null?DEFAULT_CONNECTION_URL:config));
            JDBCSessionIdManager.SessionIdTableSchema idTableSchema = new JDBCSessionIdManager.SessionIdTableSchema();
            idTableSchema.setTableName("mysessionids");
            idTableSchema.setIdColumn("myid");
            idManager.setSessionIdTableSchema(idTableSchema);
            
            JDBCSessionIdManager.SessionTableSchema sessionTableSchema = new JDBCSessionIdManager.SessionTableSchema();
            sessionTableSchema.setTableName("mysessions");
            sessionTableSchema.setIdColumn("mysessionid");
            sessionTableSchema.setAccessTimeColumn("atime");
            sessionTableSchema.setContextPathColumn("cpath");
            sessionTableSchema.setCookieTimeColumn("cooktime");
            sessionTableSchema.setCreateTimeColumn("ctime");
            sessionTableSchema.setExpiryTimeColumn("extime");
            sessionTableSchema.setLastAccessTimeColumn("latime");
            sessionTableSchema.setLastNodeColumn("lnode");
            sessionTableSchema.setLastSavedTimeColumn("lstime");
            sessionTableSchema.setMapColumn("mo");
            sessionTableSchema.setMaxIntervalColumn("mi");           
            idManager.setSessionTableSchema(sessionTableSchema);
            
            return idManager;
        }
    }

    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionManager()
     */
    @Override
    public SessionManager newSessionManager()
    {
        JDBCSessionManager manager =  new JDBCSessionManager();
        manager.setSessionIdManager((JDBCSessionIdManager)_sessionIdManager);
        manager.setSaveInterval(SAVE_INTERVAL); //ensure we save any changes to the session at least once per second
        return manager;
    }

    
    public boolean existsInSessionIdTable(String id)
    throws Exception
    {
        Class.forName(DRIVER_CLASS);
        Connection con = null;
        try
        {
            con = DriverManager.getConnection(DEFAULT_CONNECTION_URL);
            PreparedStatement statement = con.prepareStatement("select * from "+
                    ((JDBCSessionIdManager)_sessionIdManager)._sessionIdTableSchema.getTableName()+
                    " where "+((JDBCSessionIdManager)_sessionIdManager)._sessionIdTableSchema.getIdColumn()+" = ?");
            statement.setString(1, id);
            ResultSet result = statement.executeQuery();
            return result.next();
        }
        finally
        {
            if (con != null)
                con.close();
        }
    }
    
    
    public boolean existsInSessionTable(String id, boolean verbose)
    throws Exception
    {
        Class.forName(DRIVER_CLASS);
        Connection con = null;
        try
        {
            con = DriverManager.getConnection(DEFAULT_CONNECTION_URL);
            PreparedStatement statement = con.prepareStatement("select * from "+
                    ((JDBCSessionIdManager)_sessionIdManager)._sessionTableSchema.getTableName()+
                    " where "+((JDBCSessionIdManager)_sessionIdManager)._sessionTableSchema.getIdColumn()+" = ?");
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
    
    
    
    public Set<String> getSessionIds ()
    throws Exception
    {
        HashSet<String> ids = new HashSet<String>();
        Class.forName(DRIVER_CLASS);
        Connection con = null;
        try
        {
            con = DriverManager.getConnection(DEFAULT_CONNECTION_URL);
            PreparedStatement statement = con.prepareStatement("select * from "+((JDBCSessionIdManager)_sessionIdManager)._sessionIdTableSchema.getTableName());
          
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

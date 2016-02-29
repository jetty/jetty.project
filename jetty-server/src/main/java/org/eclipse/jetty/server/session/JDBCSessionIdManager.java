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

package org.eclipse.jetty.server.session;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;



/**
 * JDBCSessionIdManager
 *
 * SessionIdManager implementation that uses a database to store in-use session ids,
 * to support distributed sessions.
 *
 */
public class JDBCSessionIdManager extends org.eclipse.jetty.server.session.AbstractSessionIdManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    public final static int MAX_INTERVAL_NOT_SET = -999;

    protected final HashSet<String> _sessionIds = new HashSet<String>();
    protected Server _server;
    protected PeriodicSessionInspector _scavenger;

    private DatabaseAdaptor _dbAdaptor = new DatabaseAdaptor();

    protected SessionIdTableSchema _sessionIdTableSchema = new SessionIdTableSchema();
    
    /**
     * SessionIdTableSchema
     *
     */
    public static class SessionIdTableSchema
    {
        protected String _tableName = "JettySessionIds";
        protected String _idColumn = "id";
        protected DatabaseAdaptor _jdbc;
      
        public String getIdColumn()
        {
            return _idColumn;
        }

        public void setIdColumn(String idColumn)
        {
            checkNotNull(idColumn);
            _idColumn = idColumn;
        }

        public String getTableName()
        {
            return _tableName;
        }

        public void setTableName(String tableName)
        {
            checkNotNull(tableName);
            _tableName = tableName;
        }

        public String getInsertStatementAsString ()
        {
            return "insert into "+_tableName+" ("+_idColumn+")  values (?)";
        }

        public String getDeleteStatementAsString ()
        {
            return "delete from "+_tableName+" where "+_idColumn+" = ?";
        }

        public String getSelectStatementAsString ()
        {
            return  "select * from "+_tableName+" where "+_idColumn+" = ?";
        }
        
        public String getCreateStatementAsString ()
        {
            return "create table "+_tableName+" ("+_idColumn+" varchar(120), primary key("+_idColumn+"))";
        }
        
        protected void prepareTables (DatabaseAdaptor jdbc)
        throws Exception
        {
            _jdbc = jdbc;
            try (Connection connection = _jdbc.getConnection();
                 Statement statement = connection.createStatement())
            {
                //make the id table
                connection.setAutoCommit(true);
                DatabaseMetaData metaData = connection.getMetaData();
                _jdbc.adaptTo(metaData);
        
                
                //checking for table existence is case-sensitive, but table creation is not
                String tableName = _jdbc.convertIdentifier(getTableName());
                try (ResultSet result = metaData.getTables(null, null, tableName, null))
                {
                    if (!result.next())
                    {
                        //table does not exist, so create it
                        statement.executeUpdate(getCreateStatementAsString());
                    }
                } 
            }
        }
        
        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }
    }
  
   
    public JDBCSessionIdManager(Server server)
    {
        super(server);
    }

    public JDBCSessionIdManager(Server server, Random random)
    {
       super(server,random);
    }

    public SessionIdTableSchema getSessionIdTableSchema()
    {
        return _sessionIdTableSchema;
    }



    

    /** 
     * @see org.eclipse.jetty.server.SessionIdManager#useId(org.eclipse.jetty.server.session.Session)
     */
    @Override
    public void useId (Session session)
    {
        if (session == null)
            return;

        synchronized (_sessionIds)
        {
            String id = session.getId();
            try
            {
                insert(id);
                _sessionIds.add(id);
            }
            catch (Exception e)
            {
                LOG.warn("Problem storing session id="+id, e);
            }
        }
    }




    /** 
     * Remove the id from in-use set
     * 
     * Prevents another context from using this id
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#removeId(java.lang.String)
     */
    @Override
    public boolean removeId (String id)
    {

        if (id == null)
            return false;

        synchronized (_sessionIds)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Removing sessionid="+id);
            try
            {
                boolean remove = _sessionIds.remove(id);
                boolean dbremove = delete(id);
                return remove || dbremove;
            }
            catch (Exception e)
            {
                LOG.warn("Problem removing session id="+id, e);
                return false;
            }
        }

    }

    /**
     * Insert a new used session id into the table.
     *
     * @param id the id to put into the table
     * @throws SQLException
     */
    private void insert (String id)
    throws SQLException
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement query = connection.prepareStatement(_sessionIdTableSchema.getSelectStatementAsString()))
        {
            connection.setAutoCommit(true);
            query.setString(1, id);
            try (ResultSet result = query.executeQuery())
            {
                //only insert the id if it isn't in the db already
                if (!result.next())
                {
                    try (PreparedStatement statement = connection.prepareStatement(_sessionIdTableSchema.getInsertStatementAsString()))
                    {
                        statement.setString(1, id);
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    /**
     * Remove a session id from the table.
     *
     * @param id the id to remove from the table
     * @throws SQLException
     */
    private boolean delete (String id)
    throws SQLException
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = connection.prepareStatement(_sessionIdTableSchema.getDeleteStatementAsString()))
        {
            connection.setAutoCommit(true);
            statement.setString(1, id);
            return (statement.executeUpdate() > 0);
        }
    }
    

    /**
     * Check if a session id exists.
     *
     * @param id the id to check
     * @return true if the id exists in the table, false otherwise
     * @throws SQLException
     */
    private boolean exists (String id)
    throws SQLException
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = connection.prepareStatement(_sessionIdTableSchema.getSelectStatementAsString()))
        {
            connection.setAutoCommit(true);
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery())
            {
                return result.next();
            }
        }
    }


    @Override
    public boolean isIdInUse(String id)
    {
        if (id == null)
            return false;

        String sessionId = getId(id);
        boolean inUse = false;
        synchronized (_sessionIds)
        {
            inUse = _sessionIds.contains(sessionId);
        }

        
        if (inUse)
            return true; //optimisation - if this session is one we've been managing, we can check locally

        //otherwise, we need to go to the database to check
        try
        {
            return exists(sessionId);
        }
        catch (Exception e)
        {
            LOG.warn("Problem checking inUse for id="+sessionId, e);
            return false;
        }
    }
 

    /**
     * Invalidate the session matching the id on all contexts.
     *
     * @see org.eclipse.jetty.server.SessionIdManager#expireAll(java.lang.String)
     */
    @Override
    public void expireAll(String id)
    {       
        synchronized (_sessionIds)
        {
           super.expireAll(id);
        }
    }


    
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        synchronized (_sessionIds)
        {
            super.renewSessionId(oldClusterId, oldNodeId, request);
        }
    }

    /**
     * Get the database adaptor in order to configure it
     * @return the database adpator
     */
    public DatabaseAdaptor getDatabaseAdaptor ()
    {
        return _dbAdaptor;
    }
    
    
    
    
    /**
     * Start up the id manager.
     *
     * Makes necessary database tables and starts a Session
     * scavenger thread.
     */
    @Override
    public void doStart()
    throws Exception
    {                
        _dbAdaptor.initialize();
        _sessionIdTableSchema.prepareTables(_dbAdaptor);
        
        super.doStart();
      
    }

    /**
     * Stop
     */
    @Override
    public void doStop ()
    throws Exception
    {
        _sessionIds.clear();

        super.doStop();
    } 
   
}

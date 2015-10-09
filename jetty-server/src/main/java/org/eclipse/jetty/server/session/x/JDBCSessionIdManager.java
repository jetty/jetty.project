//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.session.x;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.naming.InitialContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;



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
    protected SessionScavenger _scavenger;

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
        super();
        _server=server;
    }

    public JDBCSessionIdManager(Server server, Random random)
    {
       super(random);
       _server=server;
    }

    public SessionIdTableSchema getSessionIdTableSchema()
    {
        return _sessionIdTableSchema;
    }

    @Override
    public String newSessionId(long seedTerm)
    {
        String id = super.newSessionId(seedTerm);
        useId(id);
        return id;
    }


    //TODO useId might not be the right paradigm
    public void useId (String id)
    {
        if (id == null)
            return;

        synchronized (_sessionIds)
        {
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
     * @see org.eclipse.jetty.server.SessionIdManager#removeId(java.lang.String)
     */
    @Override
    public void removeId (String id)
    {

        if (id == null)
            return;

        synchronized (_sessionIds)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Removing sessionid="+id);
            try
            {
                _sessionIds.remove(id);
                delete(id);
            }
            catch (Exception e)
            {
                LOG.warn("Problem removing session id="+id, e);
            }
        }

    }

    /**
     * Insert a new used session id into the table.
     *
     * @param id
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
     * @param id
     * @throws SQLException
     */
    private void delete (String id)
    throws SQLException
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = connection.prepareStatement(_sessionIdTableSchema.getDeleteStatementAsString()))
        {
            connection.setAutoCommit(true);
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }
    

    /**
     * Check if a session id exists.
     *
     * @param id
     * @return
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
     * @see org.eclipse.jetty.server.SessionIdManager#invalidateAll(java.lang.String)
     */
    @Override
    public void invalidateAll(String id)
    {
        //take the id out of the list of known sessionids for this node
        removeId(id);

        synchronized (_sessionIds)
        {
            //tell all contexts that may have a session object with this id to
            //get rid of them
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null)
                {
                    SessionManager manager = sessionHandler.getSessionManager();

                    if (manager != null)
                    {
                        manager.expire(id);
                    }
                }
            }
        }
    }


    @Override
    public void renewSessionId (String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());

        synchronized (_sessionIds)
        {
            removeId(oldClusterId);//remove the old one from the list (and database)

            //tell all contexts to update the id 
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null) 
                {
                    SessionManager manager = sessionHandler.getSessionManager();

                    if (manager != null)
                    {
                        manager.renewSessionId(oldClusterId, oldNodeId, newClusterId, getExtendedId(newClusterId, request));
                    }
                }
            }
        }
    }

 
    
    /**
     * Get the database adaptor in order to configure it
     * @return
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
        
        if (_scavenger == null)
        {
            LOG.warn("No SessionScavenger set, using defaults");
            _scavenger = new SessionScavenger();
            _scavenger.setSessionIdManager(this);
        }
        
        _scavenger.start();
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
        _scavenger.stop();
        super.doStop();
    }

  
    public void setSessionScavenger (SessionScavenger scavenger)
    {
        _scavenger = scavenger;
        _scavenger.setSessionIdManager(this);
    }
   


   
  
    private void cleanExpiredSessionIds (Set<String> expiredIds)
    throws Exception
    {
        if (expiredIds == null || expiredIds.isEmpty())
            return;

        String[] ids = expiredIds.toArray(new String[expiredIds.size()]);
        try (Connection con = getConnection())
        {
            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            con.setAutoCommit(false);

            int start = 0;
            int end = 0;
            int blocksize = _deleteBlockSize;
            int block = 0;
       
            try (Statement statement = con.createStatement())
            {
                while (end < ids.length)
                {
                    start = block*blocksize;
                    if ((ids.length -  start)  >= blocksize)
                        end = start + blocksize;
                    else
                        end = ids.length;

                    //take them out of the sessionIds table
                    statement.executeUpdate(fillInClause("delete from "+_sessionIdTableSchema.getTableName()+" where "+_sessionIdTableSchema.getIdColumn()+" in ", ids, start, end));
                    //take them out of the sessions table
                    statement.executeUpdate(fillInClause("delete from "+_sessionTableSchema.getTableName()+" where "+_sessionTableSchema.getIdColumn()+" in ", ids, start, end));
                    block++;
                }
            }
            catch (Exception e)
            {
                con.rollback();
                throw e;
            }
            con.commit();
        }
    }

    
    
    /**
     * 
     * @param sql
     * @param atoms
     * @throws Exception
     */
    private String fillInClause (String sql, String[] literals, int start, int end)
    throws Exception
    {
        StringBuffer buff = new StringBuffer();
        buff.append(sql);
        buff.append("(");
        for (int i=start; i<end; i++)
        {
            buff.append("'"+(literals[i])+"'");
            if (i+1<end)
                buff.append(",");
        }
        buff.append(")");
        return buff.toString();
    }
     
   
}

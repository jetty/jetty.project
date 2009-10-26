// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server.session;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import javax.naming.InitialContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;



/**
 * JDBCSessionIdManager
 *
 * SessionIdManager implementation that uses a database to store in-use session ids, 
 * to support distributed sessions.
 * 
 */
public class JDBCSessionIdManager extends AbstractSessionIdManager
{    
    protected final HashSet<String> _sessionIds = new HashSet();
    protected String _driverClassName;
    protected String _connectionUrl;
    protected DataSource _datasource;
    protected String _jndiName;
    protected String _sessionIdTable = "JettySessionIds";
    protected String _sessionTable = "JettySessions";
    protected Timer _timer; //scavenge timer
    protected TimerTask _task; //scavenge task
    protected long _lastScavengeTime;
    protected long _scavengeIntervalMs = 1000 * 60 * 10; //10mins
    
    
    protected String _createSessionIdTable;
    protected String _createSessionTable;
                                            
    protected String _selectExpiredSessions;
    protected String _deleteOldExpiredSessions;

    protected String _insertId;
    protected String _deleteId;
    protected String _queryId;
    
    protected DatabaseAdaptor _dbAdaptor;

    
    /**
     * DatabaseAdaptor
     *
     * Handles differences between databases.
     * 
     * Postgres uses the getBytes and setBinaryStream methods to access
     * a "bytea" datatype, which can be up to 1Gb of binary data. MySQL
     * is happy to use the "blob" type and getBlob() methods instead.
     * 
     * TODO if the differences become more major it would be worthwhile
     * refactoring this class.
     */
    public class DatabaseAdaptor 
    {
        String _dbName;
        boolean _isLower;
        boolean _isUpper;
        
        
        public DatabaseAdaptor (DatabaseMetaData dbMeta)
        throws SQLException
        {
            _dbName = dbMeta.getDatabaseProductName().toLowerCase(); 
            Log.debug ("Using database "+_dbName);
            _isLower = dbMeta.storesLowerCaseIdentifiers();
            _isUpper = dbMeta.storesUpperCaseIdentifiers();
        }
        
        /**
         * Convert a camel case identifier into either upper or lower
         * depending on the way the db stores identifiers.
         * 
         * @param identifier
         * @return
         */
        public String convertIdentifier (String identifier)
        {
            if (_isLower)
                return identifier.toLowerCase();
            if (_isUpper)
                return identifier.toUpperCase();
            
            return identifier;
        }
        
        public String getBlobType ()
        {
            if (_dbName.startsWith("postgres"))
                return "bytea";
            
            return "blob";
        }
        
        public InputStream getBlobInputStream (ResultSet result, String columnName)
        throws SQLException
        {
            if (_dbName.startsWith("postgres"))
            {
                byte[] bytes = result.getBytes(columnName);
                return new ByteArrayInputStream(bytes);
            }
            
            Blob blob = result.getBlob(columnName);
            return blob.getBinaryStream();
        }
    }
    
    
    
    public JDBCSessionIdManager(Server server)
    {
        super(server);
    }
    
    public JDBCSessionIdManager(Server server, Random random)
    {
       super(server, random);
    }

    /**
     * Configure jdbc connection information via a jdbc Driver
     * 
     * @param driverClassName
     * @param connectionUrl
     */
    public void setDriverInfo (String driverClassName, String connectionUrl)
    {
        _driverClassName=driverClassName;
        _connectionUrl=connectionUrl;
    }
    
    public String getDriverClassName()
    {
        return _driverClassName;
    }
    
    public String getConnectionUrl ()
    {
        return _connectionUrl;
    }
    
    public void setDatasourceName (String jndi)
    {
        _jndiName=jndi;
    }
    
    public String getDatasourceName ()
    {
        return _jndiName;
    }
   
    
    public void setScavengeInterval (long sec)
    {
        if (sec<=0)
            sec=60;

        long old_period=_scavengeIntervalMs;
        long period=sec*1000;
      
        _scavengeIntervalMs=period;
        
        //add a bit of variability into the scavenge time so that not all
        //nodes with the same scavenge time sync up
        long tenPercent = _scavengeIntervalMs/10;
        if ((System.currentTimeMillis()%2) == 0)
            _scavengeIntervalMs += tenPercent;
        
        if (Log.isDebugEnabled()) Log.debug("Scavenging every "+_scavengeIntervalMs+" ms");
        if (_timer!=null && (period!=old_period || _task==null))
        {
            synchronized (this)
            {
                if (_task!=null)
                    _task.cancel();
                _task = new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        scavenge();
                    }   
                };
                _timer.schedule(_task,_scavengeIntervalMs,_scavengeIntervalMs);
            }
        }  
    }
    
    public long getScavengeInterval ()
    {
        return _scavengeIntervalMs/1000;
    }
    
    
    public void addSession(HttpSession session)
    {
        if (session == null)
            return;
        
        synchronized (_sessionIds)
        {
            String id = ((JDBCSessionManager.Session)session).getClusterId();            
            try
            {
                insert(id);
                _sessionIds.add(id);
            }
            catch (Exception e)
            {
                Log.warn("Problem storing session id="+id, e);
            }
        }
    }
    
    public void removeSession(HttpSession session)
    {
        if (session == null)
            return;
        
        removeSession(((JDBCSessionManager.Session)session).getClusterId());
    }
    
    
    
    public void removeSession (String id)
    {

        if (id == null)
            return;
        
        synchronized (_sessionIds)
        {  
            if (Log.isDebugEnabled())
                Log.debug("Removing session id="+id);
            try
            {               
                _sessionIds.remove(id);
                delete(id);
            }
            catch (Exception e)
            {
                Log.warn("Problem removing session id="+id, e);
            }
        }
        
    }
    

    /** 
     * Get the session id without any node identifier suffix.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#getClusterId(java.lang.String)
     */
    public String getClusterId(String nodeId)
    {
        int dot=nodeId.lastIndexOf('.');
        return (dot>0)?nodeId.substring(0,dot):nodeId;
    }
    

    /** 
     * Get the session id, including this node's id as a suffix.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#getNodeId(java.lang.String, javax.servlet.http.HttpServletRequest)
     */
    public String getNodeId(String clusterId, HttpServletRequest request)
    {
        if (_workerName!=null)
            return clusterId+'.'+_workerName;

        return clusterId;
    }


    public boolean idInUse(String id)
    {
        if (id == null)
            return false;
        
        String clusterId = getClusterId(id);
        
        synchronized (_sessionIds)
        {
            if (_sessionIds.contains(clusterId))
                return true; //optimisation - if this session is one we've been managing, we can check locally
            
            //otherwise, we need to go to the database to check
            try
            {
                return exists(clusterId);
            }
            catch (Exception e)
            {
                Log.warn("Problem checking inUse for id="+clusterId, e);
                return false;
            }
        }
    }

    /** 
     * Invalidate the session matching the id on all contexts.
     * 
     * @see org.eclipse.jetty.server.SessionIdManager#invalidateAll(java.lang.String)
     */
    public void invalidateAll(String id)
    {            
        //take the id out of the list of known sessionids for this node
        removeSession(id);
        
        synchronized (_sessionIds)
        {
            //tell all contexts that may have a session object with this id to
            //get rid of them
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionManager manager = ((SessionHandler)((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class)).getSessionManager();
                        
                if (manager instanceof JDBCSessionManager)
                {
                    ((JDBCSessionManager)manager).invalidateSession(id);
                }
            }
        }
    }


    /** 
     * Start up the id manager.
     * 
     * Makes necessary database tables and starts a Session
     * scavenger thread.
     * 
     * @see org.eclipse.jetty.server.session.AbstractSessionIdManager#doStart()
     */
    @Override
    public void doStart()
    {
        try
        {            
            initializeDatabase();
            prepareTables();        
            super.doStart();
            if (Log.isDebugEnabled()) Log.debug("Scavenging interval = "+getScavengeInterval()+" sec");
            _timer=new Timer("JDBCSessionScavenger", true);
            setScavengeInterval(getScavengeInterval());
        }
        catch (Exception e)
        {
            Log.warn("Problem initialising JettySessionIds table", e);
        }
    }
    
    /** 
     * Stop the scavenger.
     * 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    public void doStop () 
    throws Exception
    {
        synchronized(this)
        {
            if (_task!=null)
                _task.cancel();
            if (_timer!=null)
                _timer.cancel();
            _timer=null;
        }
        super.doStop();
    }
    
    /**
     * Get a connection from the driver or datasource.
     * 
     * @return
     * @throws SQLException
     */
    protected Connection getConnection ()
    throws SQLException
    {
        if (_datasource != null)
            return _datasource.getConnection();
        else
            return DriverManager.getConnection(_connectionUrl);
    }

    
    private void initializeDatabase ()
    throws Exception
    {
        if (_jndiName!=null)
        {
            InitialContext ic = new InitialContext();
            _datasource = (DataSource)ic.lookup(_jndiName);
        }
        else if (_driverClassName!=null && _connectionUrl!=null)
        {
            Class.forName(_driverClassName);
        }
        else
            throw new IllegalStateException("No database configured for sessions");
    }
    
    
    
    /**
     * Set up the tables in the database
     * @throws SQLException
     */
    private void prepareTables()
    throws SQLException
    {
        _createSessionIdTable = "create table "+_sessionIdTable+" (id varchar(60), primary key(id))";
        _selectExpiredSessions = "select * from "+_sessionTable+" where expiryTime >= ? and expiryTime <= ?";
        _deleteOldExpiredSessions = "delete from "+_sessionTable+" where expiryTime >0 and expiryTime <= ?";

        _insertId = "insert into "+_sessionIdTable+" (id)  values (?)";
        _deleteId = "delete from "+_sessionIdTable+" where id = ?";
        _queryId = "select * from "+_sessionIdTable+" where id = ?";

        Connection connection = null;
        try
        {
            //make the id table
            connection = getConnection();
            connection.setAutoCommit(true);
            DatabaseMetaData metaData = connection.getMetaData();
            _dbAdaptor = new DatabaseAdaptor(metaData);

            //checking for table existence is case-sensitive, but table creation is not
            String tableName = _dbAdaptor.convertIdentifier(_sessionIdTable);
            ResultSet result = metaData.getTables(null, null, tableName, null);
            if (!result.next())
            {
                //table does not exist, so create it
                connection.createStatement().executeUpdate(_createSessionIdTable);
            }
            
            //make the session table if necessary
            tableName = _dbAdaptor.convertIdentifier(_sessionTable);   
            result = metaData.getTables(null, null, tableName, null);
            if (!result.next())
            {
                //table does not exist, so create it
                String blobType = _dbAdaptor.getBlobType();
                _createSessionTable = "create table "+_sessionTable+" (rowId varchar(60), sessionId varchar(60), "+
                                           " contextPath varchar(60), virtualHost varchar(60), lastNode varchar(60), accessTime bigint, "+
                                           " lastAccessTime bigint, createTime bigint, cookieTime bigint, "+
                                           " lastSavedTime bigint, expiryTime bigint, map "+blobType+", primary key(rowId))";
                connection.createStatement().executeUpdate(_createSessionTable);
            }
            
            //make some indexes on the JettySessions table
            String index1 = "idx_"+_sessionTable+"_expiry";
            String index2 = "idx_"+_sessionTable+"_session";
            
            result = metaData.getIndexInfo(null, null, tableName, false, false);
            boolean index1Exists = false;
            boolean index2Exists = false;
            while (result.next())
            {
                String idxName = result.getString("INDEX_NAME");
                if (index1.equalsIgnoreCase(idxName))
                    index1Exists = true;
                else if (index2.equalsIgnoreCase(idxName))
                    index2Exists = true;
            }
            if (!(index1Exists && index2Exists))
            {
                Statement statement = connection.createStatement();
                if (!index1Exists)
                    statement.executeUpdate("create index "+index1+" on "+_sessionTable+" (expiryTime)");
                if (!index2Exists)
                    statement.executeUpdate("create index "+index2+" on "+_sessionTable+" (sessionId, contextPath)");
            }
        }
        finally
        {
            if (connection != null)
                connection.close();
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
        Connection connection = null;
        try
        {
            connection = getConnection();
            connection.setAutoCommit(true);            
            PreparedStatement query = connection.prepareStatement(_queryId);
            query.setString(1, id);
            ResultSet result = query.executeQuery();
            //only insert the id if it isn't in the db already 
            if (!result.next())
            {
                PreparedStatement statement = connection.prepareStatement(_insertId);
                statement.setString(1, id);
                statement.executeUpdate();
            }
        }
        finally
        {
            if (connection != null)
                connection.close();
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
        Connection connection = null;
        try
        {
            connection = getConnection();
            connection.setAutoCommit(true);
            PreparedStatement statement = connection.prepareStatement(_deleteId);
            statement.setString(1, id);
            statement.executeUpdate();
        }
        finally
        {
            if (connection != null)
                connection.close();
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
        Connection connection = null;
        try
        {
            connection = getConnection();
            connection.setAutoCommit(true);
            PreparedStatement statement = connection.prepareStatement(_queryId);
            statement.setString(1, id);
            ResultSet result = statement.executeQuery();
            return result.next();
        }
        finally
        {
            if (connection != null)
                connection.close();
        }
    }
    
    /**
     * Look for sessions in the database that have expired.
     * 
     * We do this in the SessionIdManager and not the SessionManager so
     * that we only have 1 scavenger, otherwise if there are n SessionManagers
     * there would be n scavengers, all contending for the database.
     * 
     * We look first for sessions that expired in the previous interval, then
     * for sessions that expired previously - these are old sessions that no
     * node is managing any more and have become stuck in the database.
     */
    private void scavenge ()
    {
        Connection connection = null;
        List expiredSessionIds = new ArrayList();
        try
        {            
            if (Log.isDebugEnabled()) Log.debug("Scavenge sweep started at "+System.currentTimeMillis());
            if (_lastScavengeTime > 0)
            {
                connection = getConnection();
                connection.setAutoCommit(true);
                //"select sessionId from JettySessions where expiryTime > (lastScavengeTime - scanInterval) and expiryTime < lastScavengeTime";
                PreparedStatement statement = connection.prepareStatement(_selectExpiredSessions);
                long lowerBound = (_lastScavengeTime - _scavengeIntervalMs);
                long upperBound = _lastScavengeTime;
                if (Log.isDebugEnabled()) Log.debug("Searching for sessions expired between "+lowerBound + " and "+upperBound);
                statement.setLong(1, lowerBound);
                statement.setLong(2, upperBound);
                ResultSet result = statement.executeQuery();
                while (result.next())
                {
                    String sessionId = result.getString("sessionId");
                    expiredSessionIds.add(sessionId);
                    if (Log.isDebugEnabled()) Log.debug("Found expired sessionId="+sessionId);
                }


                //tell the SessionManagers to expire any sessions with a matching sessionId in memory
                Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
                for (int i=0; contexts!=null && i<contexts.length; i++)
                {
                    SessionManager manager = ((SessionHandler)((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class)).getSessionManager();
                            
                    if (manager instanceof JDBCSessionManager)
                    {
                        ((JDBCSessionManager)manager).expire(expiredSessionIds);
                    }
                }

                //find all sessions that have expired at least a couple of scanIntervals ago and just delete them
                upperBound = _lastScavengeTime - (2 * _scavengeIntervalMs);
                if (upperBound > 0)
                {
                    if (Log.isDebugEnabled()) Log.debug("Deleting old expired sessions expired before "+upperBound);
                    statement = connection.prepareStatement(_deleteOldExpiredSessions);
                    statement.setLong(1, upperBound);
                    statement.executeUpdate();
                }
            }
        }
        catch (Exception e)
        {
            Log.warn("Problem selecting expired sessions", e);
        }
        finally
        {           
            _lastScavengeTime=System.currentTimeMillis();
            if (Log.isDebugEnabled()) Log.debug("Scavenge sweep ended at "+_lastScavengeTime);
            if (connection != null)
            {
                try
                {
                connection.close();
                }
                catch (SQLException e)
                {
                    Log.warn(e);
                }
            }
        }
    }
}

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.jetty.util.log.Logger;



/**
 * JDBCSessionIdManager
 *
 * SessionIdManager implementation that uses a database to store in-use session ids, 
 * to support distributed sessions.
 * 
 */
public class JDBCSessionIdManager extends AbstractSessionIdManager
{    
    final static Logger LOG = SessionHandler.LOG;
    
    protected final HashSet<String> _sessionIds = new HashSet<String>();
    protected Server _server;
    protected Driver _driver;
    protected String _driverClassName;
    protected String _connectionUrl;
    protected DataSource _datasource;
    protected String _jndiName;
    protected String _sessionIdTable = "JettySessionIds";
    protected String _sessionTable = "JettySessions";
    protected String _sessionTableRowId = "rowId";
    
    protected Timer _timer; //scavenge timer
    protected TimerTask _task; //scavenge task
    protected long _lastScavengeTime;
    protected long _scavengeIntervalMs = 1000L * 60 * 10; //10mins
    protected String _blobType; //if not set, is deduced from the type of the database at runtime
    protected String _longType; //if not set, is deduced from the type of the database at runtime
    
    protected String _createSessionIdTable;
    protected String _createSessionTable;
                                            
    protected String _selectBoundedExpiredSessions;
    protected String _deleteOldExpiredSessions;

    protected String _insertId;
    protected String _deleteId;
    protected String _queryId;
    
    protected  String _insertSession;
    protected  String _deleteSession;
    protected  String _updateSession;
    protected  String _updateSessionNode;
    protected  String _updateSessionAccessTime;
    
    protected DatabaseAdaptor _dbAdaptor;

    private String _selectExpiredSessions;

    
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
            _dbName = dbMeta.getDatabaseProductName().toLowerCase(Locale.ENGLISH); 
            LOG.debug ("Using database {}",_dbName);
            _isLower = dbMeta.storesLowerCaseIdentifiers();
            _isUpper = dbMeta.storesUpperCaseIdentifiers();            
        }
        
        /**
         * Convert a camel case identifier into either upper or lower
         * depending on the way the db stores identifiers.
         * 
         * @param identifier
         * @return the converted identifier
         */
        public String convertIdentifier (String identifier)
        {
            if (_isLower)
                return identifier.toLowerCase(Locale.ENGLISH);
            if (_isUpper)
                return identifier.toUpperCase(Locale.ENGLISH);
            
            return identifier;
        }
        
        public String getDBName ()
        {
            return _dbName;
        }
        
        public String getBlobType ()
        {
            if (_blobType != null)
                return _blobType;
            
            if (_dbName.startsWith("postgres"))
                return "bytea";
            
            return "blob";
        }
        
        public String getLongType ()
        {
            if (_longType != null)
                return _longType;
            
            if (_dbName.startsWith("oracle"))
                return "number(20)";
            
            return "bigint";
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
        
        /**
         * rowId is a reserved word for Oracle, so change the name of this column
         * @return
         */
        public String getRowIdColumnName ()
        {
            if (_dbName != null && _dbName.startsWith("oracle"))
                return "srowId";
            
            return "rowId";
        }
        
        
        public boolean isEmptyStringNull ()
        {
            return (_dbName.startsWith("oracle"));
        }
        
        public PreparedStatement getLoadStatement (Connection connection, String rowId, String contextPath, String virtualHosts) 
        throws SQLException
        {
            if (contextPath == null || "".equals(contextPath))
            {
                if (isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement("select * from "+_sessionTable+
                    " where sessionId = ? and contextPath is null and virtualHost = ?");
                    statement.setString(1, rowId);
                    statement.setString(2, virtualHosts);

                    return statement;
                }
            }
           


            PreparedStatement statement = connection.prepareStatement("select * from "+_sessionTable+
            " where sessionId = ? and contextPath = ? and virtualHost = ?");
            statement.setString(1, rowId);
            statement.setString(2, contextPath);
            statement.setString(3, virtualHosts);

            return statement;
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
    
    /**
     * Configure jdbc connection information via a jdbc Driver
     * 
     * @param driverClass
     * @param connectionUrl
     */
    public void setDriverInfo (Driver driverClass, String connectionUrl)
    {
        _driver=driverClass;
        _connectionUrl=connectionUrl;
    }
    
    
    public void setDatasource (DataSource ds)
    {
        _datasource = ds;
    }
    
    public DataSource getDataSource ()
    {
        return _datasource;
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
   
    public void setBlobType (String name)
    {
        _blobType = name;
    }
    
    public String getBlobType ()
    {
        return _blobType;
    }
    
    
    
    public String getLongType()
    {
        return _longType;
    }

    public void setLongType(String longType)
    {
        this._longType = longType;
    }

    public void setScavengeInterval (long sec)
    {
        if (sec<=0)
            sec=60;

        long old_period=_scavengeIntervalMs;
        long period=sec*1000L;
      
        _scavengeIntervalMs=period;
        
        //add a bit of variability into the scavenge time so that not all
        //nodes with the same scavenge time sync up
        long tenPercent = _scavengeIntervalMs/10;
        if ((System.currentTimeMillis()%2) == 0)
            _scavengeIntervalMs += tenPercent;
        
        if (LOG.isDebugEnabled()) 
            LOG.debug("Scavenging every "+_scavengeIntervalMs+" ms");
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
                LOG.warn("Problem storing session id="+id, e);
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
            if (LOG.isDebugEnabled())
                LOG.debug("Removing session id="+id);
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
        boolean inUse = false;
        synchronized (_sessionIds)
        {
            inUse = _sessionIds.contains(clusterId);
        }
        
        
        if (inUse)
            return true; //optimisation - if this session is one we've been managing, we can check locally

        //otherwise, we need to go to the database to check
        try
        {
            return exists(clusterId);
        }
        catch (Exception e)
        {
            LOG.warn("Problem checking inUse for id="+clusterId, e);
            return false;
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
                SessionHandler sessionHandler = (SessionHandler)((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null) 
                {
                    SessionManager manager = sessionHandler.getSessionManager();

                    if (manager != null && manager instanceof JDBCSessionManager)
                    {
                        ((JDBCSessionManager)manager).invalidateSession(id);
                    }
                }
            }
        }
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
        initializeDatabase();
        prepareTables();   
        cleanExpiredSessions();
        super.doStart();
        if (LOG.isDebugEnabled()) 
            LOG.debug("Scavenging interval = "+getScavengeInterval()+" sec");
        _timer=new Timer("JDBCSessionScavenger", true);
        setScavengeInterval(getScavengeInterval());
    }

    /** 
     * Stop the scavenger.
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
        _sessionIds.clear();
        super.doStop();
    }
  
    /**
     * Get a connection from the driver or datasource.
     * 
     * @return the connection for the datasource
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
    
    
   
    
    
    /**
     * Set up the tables in the database
     * @throws SQLException
     */
    private void prepareTables()
    throws SQLException
    {
        _createSessionIdTable = "create table "+_sessionIdTable+" (id varchar(120), primary key(id))";
        _selectBoundedExpiredSessions = "select * from "+_sessionTable+" where expiryTime >= ? and expiryTime <= ?";
        _selectExpiredSessions = "select * from "+_sessionTable+" where expiryTime >0 and expiryTime <= ?";
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
            _sessionTableRowId = _dbAdaptor.getRowIdColumnName();

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
                String longType = _dbAdaptor.getLongType();
                _createSessionTable = "create table "+_sessionTable+" ("+_sessionTableRowId+" varchar(120), sessionId varchar(120), "+
                                           " contextPath varchar(60), virtualHost varchar(60), lastNode varchar(60), accessTime "+longType+", "+
                                           " lastAccessTime "+longType+", createTime "+longType+", cookieTime "+longType+", "+
                                           " lastSavedTime "+longType+", expiryTime "+longType+", map "+blobType+", primary key("+_sessionTableRowId+"))";
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
                try
                {
                    if (!index1Exists)
                        statement.executeUpdate("create index "+index1+" on "+_sessionTable+" (expiryTime)");
                    if (!index2Exists)
                        statement.executeUpdate("create index "+index2+" on "+_sessionTable+" (sessionId, contextPath)");
                }
                finally
                {
                    if (statement!=null)
                    {
                        try { statement.close(); }
                        catch(Exception e) { LOG.warn(e); }
                    }
                }
            }

            //set up some strings representing the statements for session manipulation
            _insertSession = "insert into "+_sessionTable+
            " ("+_sessionTableRowId+", sessionId, contextPath, virtualHost, lastNode, accessTime, lastAccessTime, createTime, cookieTime, lastSavedTime, expiryTime, map) "+
            " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            _deleteSession = "delete from "+_sessionTable+
            " where "+_sessionTableRowId+" = ?";
            
            _updateSession = "update "+_sessionTable+
            " set lastNode = ?, accessTime = ?, lastAccessTime = ?, lastSavedTime = ?, expiryTime = ?, map = ? where "+_sessionTableRowId+" = ?";

            _updateSessionNode = "update "+_sessionTable+
            " set lastNode = ? where "+_sessionTableRowId+" = ?";

            _updateSessionAccessTime = "update "+_sessionTable+
            " set lastNode = ?, accessTime = ?, lastAccessTime = ?, lastSavedTime = ?, expiryTime = ? where "+_sessionTableRowId+" = ?";

            
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
        PreparedStatement statement = null;
        PreparedStatement query = null;
        try
        {
            connection = getConnection();
            connection.setAutoCommit(true);            
            query = connection.prepareStatement(_queryId);
            query.setString(1, id);
            ResultSet result = query.executeQuery();
            //only insert the id if it isn't in the db already 
            if (!result.next())
            {
                statement = connection.prepareStatement(_insertId);
                statement.setString(1, id);
                statement.executeUpdate();
            }
        }
        finally
        {
            if (query!=null)
            {
                try { query.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

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
        PreparedStatement statement = null;
        try
        {
            connection = getConnection();
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_deleteId);
            statement.setString(1, id);
            statement.executeUpdate();
        }
        finally
        {
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

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
        PreparedStatement statement = null;
        try
        {
            connection = getConnection();
            connection.setAutoCommit(true);
            statement = connection.prepareStatement(_queryId);
            statement.setString(1, id);
            ResultSet result = statement.executeQuery();
            return result.next();
        }
        finally
        {
            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

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
        PreparedStatement statement = null;
        List<String> expiredSessionIds = new ArrayList<String>();
        try
        {            
            if (LOG.isDebugEnabled()) 
                LOG.debug("Scavenge sweep started at "+System.currentTimeMillis());
            if (_lastScavengeTime > 0)
            {
                connection = getConnection();
                connection.setAutoCommit(true);
                //"select sessionId from JettySessions where expiryTime > (lastScavengeTime - scanInterval) and expiryTime < lastScavengeTime";
                statement = connection.prepareStatement(_selectBoundedExpiredSessions);
                long lowerBound = (_lastScavengeTime - _scavengeIntervalMs);
                long upperBound = _lastScavengeTime;
                if (LOG.isDebugEnabled()) 
                    LOG.debug (" Searching for sessions expired between "+lowerBound + " and "+upperBound);
                
                statement.setLong(1, lowerBound);
                statement.setLong(2, upperBound);
                ResultSet result = statement.executeQuery();
                while (result.next())
                {
                    String sessionId = result.getString("sessionId");
                    expiredSessionIds.add(sessionId);
                    if (LOG.isDebugEnabled()) LOG.debug (" Found expired sessionId="+sessionId); 
                }

                //tell the SessionManagers to expire any sessions with a matching sessionId in memory
                Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
                for (int i=0; contexts!=null && i<contexts.length; i++)
                {

                    SessionHandler sessionHandler = (SessionHandler)((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                    if (sessionHandler != null) 
                    { 
                        SessionManager manager = sessionHandler.getSessionManager();
                        if (manager != null && manager instanceof JDBCSessionManager)
                        {
                            ((JDBCSessionManager)manager).expire(expiredSessionIds);
                        }
                    }
                }

                //find all sessions that have expired at least a couple of scanIntervals ago and just delete them
                upperBound = _lastScavengeTime - (2 * _scavengeIntervalMs);
                if (upperBound > 0)
                {
                    if (LOG.isDebugEnabled()) LOG.debug("Deleting old expired sessions expired before "+upperBound);
                    try
                    {
                        statement = connection.prepareStatement(_deleteOldExpiredSessions);
                        statement.setLong(1, upperBound);
                        int rows = statement.executeUpdate();
                        if (LOG.isDebugEnabled()) LOG.debug("Deleted "+rows+" rows of old sessions expired before "+upperBound);
                    }
                    finally
                    {
                        if (statement!=null)
                        {
                            try { statement.close(); }
                            catch(Exception e) { LOG.warn(e); }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            if (isRunning())    
                LOG.warn("Problem selecting expired sessions", e);
            else
                LOG.ignore(e);
        }
        finally
        {           
            _lastScavengeTime=System.currentTimeMillis();
            if (LOG.isDebugEnabled()) LOG.debug("Scavenge sweep ended at "+_lastScavengeTime);
            if (connection != null)
            {
                try
                {
                connection.close();
                }
                catch (SQLException e)
                {
                    LOG.warn(e);
                }
            }
        }
    }
    
    /**
     * Get rid of sessions and sessionids from sessions that have already expired
     * @throws Exception
     */
    private void cleanExpiredSessions ()
    {
        Connection connection = null;
        PreparedStatement statement = null;
        Statement sessionsTableStatement = null;
        Statement sessionIdsTableStatement = null;
        List<String> expiredSessionIds = new ArrayList<String>();
        try
        {     
            connection = getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);

            statement = connection.prepareStatement(_selectExpiredSessions);
            long now = System.currentTimeMillis();
            if (LOG.isDebugEnabled()) LOG.debug ("Searching for sessions expired before {}", now);

            statement.setLong(1, now);
            ResultSet result = statement.executeQuery();
            while (result.next())
            {
                String sessionId = result.getString("sessionId");
                expiredSessionIds.add(sessionId);
                if (LOG.isDebugEnabled()) LOG.debug ("Found expired sessionId={}", sessionId); 
            }
            
            sessionsTableStatement = null;
            sessionIdsTableStatement = null;

            if (!expiredSessionIds.isEmpty())
            {
                sessionsTableStatement = connection.createStatement();
                sessionsTableStatement.executeUpdate(createCleanExpiredSessionsSql("delete from "+_sessionTable+" where sessionId in ", expiredSessionIds));
                sessionIdsTableStatement = connection.createStatement();
                sessionIdsTableStatement.executeUpdate(createCleanExpiredSessionsSql("delete from "+_sessionIdTable+" where id in ", expiredSessionIds));
            }
            connection.commit();

            synchronized (_sessionIds)
            {
                _sessionIds.removeAll(expiredSessionIds); //in case they were in our local cache of session ids
            }
        }
        catch (Exception e)
        {
            if (connection != null)
            {
                try 
                { 
                    LOG.warn("Rolling back clean of expired sessions", e);
                    connection.rollback();
                }
                catch (Exception x) { LOG.warn("Rollback of expired sessions failed", x);}
            }
        }
        finally
        {
            if (sessionIdsTableStatement!=null)
            {
                try { sessionIdsTableStatement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            if (sessionsTableStatement!=null)
            {
                try { sessionsTableStatement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            if (statement!=null)
            {
                try { statement.close(); }
                catch(Exception e) { LOG.warn(e); }
            }

            try
            {
                if (connection != null)
                    connection.close();
            }
            catch (SQLException e)
            {
                LOG.warn(e);
            }
        }
    }
    
    
    /**
     * 
     * @param sql
     * @param connection
     * @param expiredSessionIds
     * @throws Exception
     */
    private String createCleanExpiredSessionsSql (String sql,Collection<String> expiredSessionIds)
    throws Exception
    {
        StringBuffer buff = new StringBuffer();
        buff.append(sql);
        buff.append("(");
        Iterator<String> itor = expiredSessionIds.iterator();
        while (itor.hasNext())
        {
            buff.append("'"+(itor.next())+"'");
            if (itor.hasNext())
                buff.append(",");
        }
        buff.append(")");
        
        if (LOG.isDebugEnabled()) LOG.debug("Cleaning expired sessions with: {}", buff);
        return buff.toString();
    }
    
    private void initializeDatabase ()
    throws Exception
    {
        if (_datasource != null)
            return; //already set up
        
        if (_jndiName!=null)
        {
            InitialContext ic = new InitialContext();
            _datasource = (DataSource)ic.lookup(_jndiName);
        }
        else if ( _driver != null && _connectionUrl != null )
        {
            DriverManager.registerDriver(_driver);
        }
        else if (_driverClassName != null && _connectionUrl != null)
        {
            Class.forName(_driverClassName);
        }
        else
            throw new IllegalStateException("No database configured for sessions");
    }
    
   
}

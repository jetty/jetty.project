//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
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
    public final static int MAX_INTERVAL_NOT_SET = -999;

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
    protected int _deleteBlockSize = 10; //number of ids to include in where 'in' clause

    protected Timer _timer; //scavenge timer
    protected TimerTask _task; //scavenge task
    protected long _lastScavengeTime;
    protected long _scavengeIntervalMs = 1000L * 60 * 10; //10mins
    protected String _blobType; //if not set, is deduced from the type of the database at runtime
    protected String _longType; //if not set, is deduced from the type of the database at runtime

    protected String _createSessionIdTable;
    protected String _createSessionTable;

    protected String _selectBoundedExpiredSessions;

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
    
    public void setDeleteBlockSize (int bsize)
    {
        this._deleteBlockSize = bsize;
    }

    public int getDeleteBlockSize ()
    {
        return this._deleteBlockSize;
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


    @Override
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
    
  
    public void addSession(String id)
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



    @Override
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


    @Override
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
    @Override
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
                SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
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


    @Override
    public void renewSessionId (String oldClusterId, String oldNodeId, HttpServletRequest request)
    {
        //generate a new id
        String newClusterId = newSessionId(request.hashCode());

        synchronized (_sessionIds)
        {
            removeSession(oldClusterId);//remove the old one from the list (and database)
            addSession(newClusterId); //add in the new session id to the list (and database)

            //tell all contexts to update the id 
            Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
            for (int i=0; contexts!=null && i<contexts.length; i++)
            {
                SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
                if (sessionHandler != null) 
                {
                    SessionManager manager = sessionHandler.getSessionManager();

                    if (manager != null && manager instanceof JDBCSessionManager)
                    {
                        ((JDBCSessionManager)manager).renewSessionId(oldClusterId, oldNodeId, newClusterId, getNodeId(newClusterId, request));
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
            _task=null;
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
    /**
     * @throws SQLException
     */
    private void prepareTables()
    throws SQLException
    {
        _createSessionIdTable = "create table "+_sessionIdTable+" (id varchar(120), primary key(id))";
        _selectBoundedExpiredSessions = "select * from "+_sessionTable+" where lastNode = ? and expiryTime >= ? and expiryTime <= ?";
        _selectExpiredSessions = "select * from "+_sessionTable+" where expiryTime >0 and expiryTime <= ?";

        _insertId = "insert into "+_sessionIdTable+" (id)  values (?)";
        _deleteId = "delete from "+_sessionIdTable+" where id = ?";
        _queryId = "select * from "+_sessionIdTable+" where id = ?";

        try (Connection connection = getConnection();
                Statement statement = connection.createStatement())
        {
            //make the id table
            connection.setAutoCommit(true);
            DatabaseMetaData metaData = connection.getMetaData();
            _dbAdaptor = new DatabaseAdaptor(metaData);
            _sessionTableRowId = _dbAdaptor.getRowIdColumnName();

            //checking for table existence is case-sensitive, but table creation is not
            String tableName = _dbAdaptor.convertIdentifier(_sessionIdTable);
            try (ResultSet result = metaData.getTables(null, null, tableName, null))
            {
                if (!result.next())
                {
                    //table does not exist, so create it
                    statement.executeUpdate(_createSessionIdTable);
                }
            }
            //make the session table if necessary
            tableName = _dbAdaptor.convertIdentifier(_sessionTable);
            try (ResultSet result = metaData.getTables(null, null, tableName, null))
            {
                if (!result.next())
                {
                    //table does not exist, so create it
                    String blobType = _dbAdaptor.getBlobType();
                    String longType = _dbAdaptor.getLongType();
                    _createSessionTable = "create table "+_sessionTable+" ("+_sessionTableRowId+" varchar(120), sessionId varchar(120), "+
                                               " contextPath varchar(60), virtualHost varchar(60), lastNode varchar(60), accessTime "+longType+", "+
                                               " lastAccessTime "+longType+", createTime "+longType+", cookieTime "+longType+", "+
                                               " lastSavedTime "+longType+", expiryTime "+longType+", maxInterval "+longType+", map "+blobType+", primary key("+_sessionTableRowId+"))";
                    statement.executeUpdate(_createSessionTable);
                }
                else
                {
                    //session table exists, check it has maxinterval column
                    ResultSet colResult = null;
                    try
                    {
                        colResult = metaData.getColumns(null, null,_dbAdaptor.convertIdentifier(_sessionTable), _dbAdaptor.convertIdentifier("maxInterval"));
                    }
                    catch (SQLException s)
                    {
                        LOG.warn("Problem checking if "+_sessionTable+" table contains maxInterval column. Ensure table contains column definition: \"maxInterval long not null default -999\"");
                        throw s;
                    }
                    try
                    {
                        if (!colResult.next())
                        {
                            try
                            {
                                //add the maxinterval column
                                String longType = _dbAdaptor.getLongType();
                                statement.executeUpdate("alter table "+_sessionTable+" add maxInterval "+longType+" not null default "+MAX_INTERVAL_NOT_SET);
                            }
                            catch (SQLException s)
                            {
                                LOG.warn("Problem adding maxInterval column. Ensure table contains column definition: \"maxInterval long not null default -999\"");
                                throw s;
                            }
                        }
                    }
                    finally
                    {
                        colResult.close();
                    }
                }
            }
            //make some indexes on the JettySessions table
            String index1 = "idx_"+_sessionTable+"_expiry";
            String index2 = "idx_"+_sessionTable+"_session";

            boolean index1Exists = false;
            boolean index2Exists = false;
            try (ResultSet result = metaData.getIndexInfo(null, null, tableName, false, false))
            {
                while (result.next())
                {
                    String idxName = result.getString("INDEX_NAME");
                    if (index1.equalsIgnoreCase(idxName))
                        index1Exists = true;
                    else if (index2.equalsIgnoreCase(idxName))
                        index2Exists = true;
                }
            }
            if (!index1Exists)
                statement.executeUpdate("create index "+index1+" on "+_sessionTable+" (expiryTime)");
            if (!index2Exists)
                statement.executeUpdate("create index "+index2+" on "+_sessionTable+" (sessionId, contextPath)");

            //set up some strings representing the statements for session manipulation
            _insertSession = "insert into "+_sessionTable+
            " ("+_sessionTableRowId+", sessionId, contextPath, virtualHost, lastNode, accessTime, lastAccessTime, createTime, cookieTime, lastSavedTime, expiryTime, maxInterval, map) "+
            " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            _deleteSession = "delete from "+_sessionTable+
            " where "+_sessionTableRowId+" = ?";

            _updateSession = "update "+_sessionTable+
            " set sessionId = ?, lastNode = ?, accessTime = ?, lastAccessTime = ?, lastSavedTime = ?, expiryTime = ?, maxInterval = ?, map = ? where "+_sessionTableRowId+" = ?";

            _updateSessionNode = "update "+_sessionTable+
            " set lastNode = ? where "+_sessionTableRowId+" = ?";

            _updateSessionAccessTime = "update "+_sessionTable+
            " set lastNode = ?, accessTime = ?, lastAccessTime = ?, lastSavedTime = ?, expiryTime = ?, maxInterval = ? where "+_sessionTableRowId+" = ?";


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
        try (Connection connection = getConnection();
                PreparedStatement query = connection.prepareStatement(_queryId))
        {
            connection.setAutoCommit(true);
            query.setString(1, id);
            try (ResultSet result = query.executeQuery())
            {
                //only insert the id if it isn't in the db already
                if (!result.next())
                {
                    try (PreparedStatement statement = connection.prepareStatement(_insertId))
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
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(_deleteId))
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
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(_queryId))
        {
            connection.setAutoCommit(true);
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery())
            {
                return result.next();
            }
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
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug(getWorkerName()+"- Scavenge sweep started at "+System.currentTimeMillis());
            if (_lastScavengeTime > 0)
            {
                connection = getConnection();
                connection.setAutoCommit(true);
                Set<String> expiredSessionIds = new HashSet<String>();
                
                
                //Pass 1: find sessions for which we were last managing node that have just expired since last pass
                long lowerBound = (_lastScavengeTime - _scavengeIntervalMs);
                long upperBound = _lastScavengeTime;
                if (LOG.isDebugEnabled())
                    LOG.debug (getWorkerName()+"- Pass 1: Searching for sessions expired between "+lowerBound + " and "+upperBound);

                try (PreparedStatement statement = connection.prepareStatement(_selectBoundedExpiredSessions))
                {
                    statement.setString(1, getWorkerName());
                    statement.setLong(2, lowerBound);
                    statement.setLong(3, upperBound);
                    try (ResultSet result = statement.executeQuery())
                    {
                        while (result.next())
                        {
                            String sessionId = result.getString("sessionId");
                            expiredSessionIds.add(sessionId);
                            if (LOG.isDebugEnabled()) LOG.debug ("Found expired sessionId="+sessionId);
                        }
                    }
                }
                scavengeSessions(expiredSessionIds, false);


                //Pass 2: find sessions that have expired a while ago for which this node was their last manager
                try (PreparedStatement selectExpiredSessions = connection.prepareStatement(_selectExpiredSessions))
                {
                    expiredSessionIds.clear();
                    upperBound = _lastScavengeTime - (2 * _scavengeIntervalMs);
                    if (upperBound > 0)
                    {
                        if (LOG.isDebugEnabled()) LOG.debug(getWorkerName()+"- Pass 2: Searching for sessions expired before "+upperBound);
                        selectExpiredSessions.setLong(1, upperBound);
                        try (ResultSet result = selectExpiredSessions.executeQuery())
                        {
                            while (result.next())
                            {
                                String sessionId = result.getString("sessionId");
                                String lastNode = result.getString("lastNode");
                                if ((getWorkerName() == null && lastNode == null) || (getWorkerName() != null && getWorkerName().equals(lastNode)))
                                    expiredSessionIds.add(sessionId);
                                if (LOG.isDebugEnabled()) LOG.debug ("Found expired sessionId="+sessionId+" last managed by "+getWorkerName());
                            }
                        }
                        scavengeSessions(expiredSessionIds, false);
                    }


                    //Pass 3:
                    //find all sessions that have expired at least a couple of scanIntervals ago
                    //if we did not succeed in loading them (eg their related context no longer exists, can't be loaded etc) then
                    //they are simply deleted
                    upperBound = _lastScavengeTime - (3 * _scavengeIntervalMs);
                    expiredSessionIds.clear();
                    if (upperBound > 0)
                    {
                        if (LOG.isDebugEnabled()) LOG.debug(getWorkerName()+"- Pass 3: searching for sessions expired before "+upperBound);
                        selectExpiredSessions.setLong(1, upperBound);
                        try (ResultSet result = selectExpiredSessions.executeQuery())
                        {
                            while (result.next())
                            {
                                String sessionId = result.getString("sessionId");
                                expiredSessionIds.add(sessionId);
                                if (LOG.isDebugEnabled()) LOG.debug ("Found expired sessionId="+sessionId);
                            }
                        }
                        scavengeSessions(expiredSessionIds, true);
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
            if (LOG.isDebugEnabled()) LOG.debug(getWorkerName()+"- Scavenge sweep ended at "+_lastScavengeTime);
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
     * @param expiredSessionIds
     */
    private void scavengeSessions (Set<String> expiredSessionIds, boolean forceDelete)
    {       
        Set<String> remainingIds = new HashSet<String>(expiredSessionIds);
        Handler[] contexts = _server.getChildHandlersByClass(ContextHandler.class);
        for (int i=0; contexts!=null && i<contexts.length; i++)
        {
            SessionHandler sessionHandler = ((ContextHandler)contexts[i]).getChildHandlerByClass(SessionHandler.class);
            if (sessionHandler != null)
            {
                SessionManager manager = sessionHandler.getSessionManager();
                if (manager != null && manager instanceof JDBCSessionManager)
                {
                    Set<String> successfullyExpiredIds = ((JDBCSessionManager)manager).expire(expiredSessionIds);
                    if (successfullyExpiredIds != null)
                        remainingIds.removeAll(successfullyExpiredIds);
                }
            }
        }

        //Any remaining ids are of those sessions that no context removed
        if (!remainingIds.isEmpty() && forceDelete)
        {
            LOG.info("Forcibly deleting unrecoverable expired sessions {}", remainingIds);
            try
            {
                //ensure they aren't in the local list of in-use session ids
                synchronized (_sessionIds)
                {
                    _sessionIds.removeAll(remainingIds);
                }
                
                cleanExpiredSessionIds(remainingIds);
            }
            catch (Exception e)
            {
                LOG.warn("Error removing expired session ids", e);
            }
        }
    }


   
    
    private void cleanExpiredSessionIds (Set<String> expiredIds)
    throws Exception
    {
        if (expiredIds == null || expiredIds.isEmpty())
            return;

        String[] ids = expiredIds.toArray(new String[expiredIds.size()]);
        try (Connection con = getConnection())
        {
            con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
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
                    statement.executeUpdate(fillInClause("delete from "+_sessionIdTable+" where id in ", ids, start, end));
                    //take them out of the sessions table
                    statement.executeUpdate(fillInClause("delete from "+_sessionTable+" where sessionId in ", ids, start, end));
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

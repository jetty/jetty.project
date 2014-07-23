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

    protected int _deleteBlockSize = 10; //number of ids to include in where 'in' clause

    protected Scheduler.Task _task; //scavenge task
    protected Scheduler _scheduler;
    protected Scavenger _scavenger;
    protected boolean _ownScheduler;
    protected long _lastScavengeTime;
    protected long _scavengeIntervalMs = 1000L * 60 * 10; //10mins


    protected String _createSessionIdTable;
    protected String _createSessionTable;

    protected String _selectBoundedExpiredSessions;
    private String _selectExpiredSessions;
    
    protected String _insertId;
    protected String _deleteId;
    protected String _queryId;

    protected  String _insertSession;
    protected  String _deleteSession;
    protected  String _updateSession;
    protected  String _updateSessionNode;
    protected  String _updateSessionAccessTime;

    protected DatabaseAdaptor _dbAdaptor = new DatabaseAdaptor();
    protected SessionIdTableSchema _sessionIdTableSchema = new SessionIdTableSchema();
    protected SessionTableSchema _sessionTableSchema = new SessionTableSchema();
    
  

 
    /**
     * SessionTableSchema
     *
     */
    public static class SessionTableSchema
    {        
        protected DatabaseAdaptor _dbAdaptor;
        protected String _tableName = "JettySessions";
        protected String _rowIdColumn = "rowId";
        protected String _idColumn = "sessionId";
        protected String _contextPathColumn = "contextPath";
        protected String _virtualHostColumn = "virtualHost"; 
        protected String _lastNodeColumn = "lastNode";
        protected String _accessTimeColumn = "accessTime"; 
        protected String _lastAccessTimeColumn = "lastAccessTime";
        protected String _createTimeColumn = "createTime";
        protected String _cookieTimeColumn = "cookieTime";
        protected String _lastSavedTimeColumn = "lastSavedTime";
        protected String _expiryTimeColumn = "expiryTime";
        protected String _maxIntervalColumn = "maxInterval";
        protected String _mapColumn = "map";
        
        
        protected void setDatabaseAdaptor(DatabaseAdaptor dbadaptor)
        {
            _dbAdaptor = dbadaptor;
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
        public String getRowIdColumn()
        {       
            if ("rowId".equals(_rowIdColumn) && _dbAdaptor.isRowIdReserved())
                _rowIdColumn = "srowId";
            return _rowIdColumn;
        }
        public void setRowIdColumn(String rowIdColumn)
        {
            checkNotNull(rowIdColumn);
            if (_dbAdaptor == null)
                throw new IllegalStateException ("DbAdaptor is null");
            
            if (_dbAdaptor.isRowIdReserved() && "rowId".equals(rowIdColumn))
                throw new IllegalArgumentException("rowId is reserved word for Oracle");
            
            _rowIdColumn = rowIdColumn;
        }
        public String getIdColumn()
        {
            return _idColumn;
        }
        public void setIdColumn(String idColumn)
        {
            checkNotNull(idColumn);
            _idColumn = idColumn;
        }
        public String getContextPathColumn()
        {
            return _contextPathColumn;
        }
        public void setContextPathColumn(String contextPathColumn)
        {
            checkNotNull(contextPathColumn);
            _contextPathColumn = contextPathColumn;
        }
        public String getVirtualHostColumn()
        {
            return _virtualHostColumn;
        }
        public void setVirtualHostColumn(String virtualHostColumn)
        {
            checkNotNull(virtualHostColumn);
            _virtualHostColumn = virtualHostColumn;
        }
        public String getLastNodeColumn()
        {
            return _lastNodeColumn;
        }
        public void setLastNodeColumn(String lastNodeColumn)
        {
            checkNotNull(lastNodeColumn);
            _lastNodeColumn = lastNodeColumn;
        }
        public String getAccessTimeColumn()
        {
            return _accessTimeColumn;
        }
        public void setAccessTimeColumn(String accessTimeColumn)
        {
            checkNotNull(accessTimeColumn);
            _accessTimeColumn = accessTimeColumn;
        }
        public String getLastAccessTimeColumn()
        {
            return _lastAccessTimeColumn;
        }
        public void setLastAccessTimeColumn(String lastAccessTimeColumn)
        {
            checkNotNull(lastAccessTimeColumn);
            _lastAccessTimeColumn = lastAccessTimeColumn;
        }
        public String getCreateTimeColumn()
        {
            return _createTimeColumn;
        }
        public void setCreateTimeColumn(String createTimeColumn)
        {
            checkNotNull(createTimeColumn);
            _createTimeColumn = createTimeColumn;
        }
        public String getCookieTimeColumn()
        {
            return _cookieTimeColumn;
        }
        public void setCookieTimeColumn(String cookieTimeColumn)
        {
            checkNotNull(cookieTimeColumn);
            _cookieTimeColumn = cookieTimeColumn;
        }
        public String getLastSavedTimeColumn()
        {
            return _lastSavedTimeColumn;
        }
        public void setLastSavedTimeColumn(String lastSavedTimeColumn)
        {
            checkNotNull(lastSavedTimeColumn);
            _lastSavedTimeColumn = lastSavedTimeColumn;
        }
        public String getExpiryTimeColumn()
        {
            return _expiryTimeColumn;
        }
        public void setExpiryTimeColumn(String expiryTimeColumn)
        {
            checkNotNull(expiryTimeColumn);
            _expiryTimeColumn = expiryTimeColumn;
        }
        public String getMaxIntervalColumn()
        {
            return _maxIntervalColumn;
        }
        public void setMaxIntervalColumn(String maxIntervalColumn)
        {
            checkNotNull(maxIntervalColumn);
            _maxIntervalColumn = maxIntervalColumn;
        }
        public String getMapColumn()
        {
            return _mapColumn;
        }
        public void setMapColumn(String mapColumn)
        {
            checkNotNull(mapColumn);
            _mapColumn = mapColumn;
        }
        
        public String getCreateStatementAsString ()
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException ("No DBAdaptor");
            
            String blobType = _dbAdaptor.getBlobType();
            String longType = _dbAdaptor.getLongType();
            
            return "create table "+_tableName+" ("+getRowIdColumn()+" varchar(120), "+_idColumn+" varchar(120), "+
                    _contextPathColumn+" varchar(60), "+_virtualHostColumn+" varchar(60), "+_lastNodeColumn+" varchar(60), "+_accessTimeColumn+" "+longType+", "+
                    _lastAccessTimeColumn+" "+longType+", "+_createTimeColumn+" "+longType+", "+_cookieTimeColumn+" "+longType+", "+
                    _lastSavedTimeColumn+" "+longType+", "+_expiryTimeColumn+" "+longType+", "+_maxIntervalColumn+" "+longType+", "+
                    _mapColumn+" "+blobType+", primary key("+getRowIdColumn()+"))";
        }
        
        public String getCreateIndexOverExpiryStatementAsString (String indexName)
        {
            return "create index "+indexName+" on "+getTableName()+" ("+getExpiryTimeColumn()+")";
        }
        
        public String getCreateIndexOverSessionStatementAsString (String indexName)
        {
            return "create index "+indexName+" on "+getTableName()+" ("+getIdColumn()+", "+getContextPathColumn()+")";
        }
        
        public String getAlterTableForMaxIntervalAsString ()
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException ("No DBAdaptor");
            String longType = _dbAdaptor.getLongType();
            return "alter table "+getTableName()+" add "+getMaxIntervalColumn()+" "+longType+" not null default "+MAX_INTERVAL_NOT_SET;
        }
        
        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }
        public String getInsertSessionStatementAsString()
        {
           return "insert into "+getTableName()+
            " ("+getRowIdColumn()+", "+getIdColumn()+", "+getContextPathColumn()+", "+getVirtualHostColumn()+", "+getLastNodeColumn()+
            ", "+getAccessTimeColumn()+", "+getLastAccessTimeColumn()+", "+getCreateTimeColumn()+", "+getCookieTimeColumn()+
            ", "+getLastSavedTimeColumn()+", "+getExpiryTimeColumn()+", "+getMaxIntervalColumn()+", "+getMapColumn()+") "+
            " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }
        public String getDeleteSessionStatementAsString()
        {
            return "delete from "+getTableName()+
            " where "+getRowIdColumn()+" = ?";
        }
        public String getUpdateSessionStatementAsString()
        {
            return "update "+getTableName()+
                    " set "+getIdColumn()+" = ?, "+getLastNodeColumn()+" = ?, "+getAccessTimeColumn()+" = ?, "+
                    getLastAccessTimeColumn()+" = ?, "+getLastSavedTimeColumn()+" = ?, "+getExpiryTimeColumn()+" = ?, "+
                    getMaxIntervalColumn()+" = ?, "+getMapColumn()+" = ? where "+getRowIdColumn()+" = ?";
        }
        public String getUpdateSessionNodeStatementAsString()
        {
            return "update "+getTableName()+
                    " set "+getLastNodeColumn()+" = ? where "+getRowIdColumn()+" = ?";
        }
        public String getUpdateSessionAccessTimeStatementAsString()
        {
           return "update "+getTableName()+
            " set "+getLastNodeColumn()+" = ?, "+getAccessTimeColumn()+" = ?, "+getLastAccessTimeColumn()+" = ?, "+
                   getLastSavedTimeColumn()+" = ?, "+getExpiryTimeColumn()+" = ?, "+getMaxIntervalColumn()+" = ? where "+getRowIdColumn()+" = ?";
        }
        
        public String getBoundedExpiredSessionsStatementAsString()
        {
            return "select * from "+getTableName()+" where "+getLastNodeColumn()+" = ? and "+getExpiryTimeColumn()+" >= ? and "+getExpiryTimeColumn()+" <= ?";
        }
        
        public String getSelectExpiredSessionsStatementAsString()
        {
            return "select * from "+getTableName()+" where "+getExpiryTimeColumn()+" >0 and "+getExpiryTimeColumn()+" <= ?";
        }
     
        public PreparedStatement getLoadStatement (Connection connection, String rowId, String contextPath, String virtualHosts)
        throws SQLException
        { 
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");


            if (contextPath == null || "".equals(contextPath))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement("select * from "+getTableName()+
                                                                              " where "+getIdColumn()+" = ? and "+
                                                                              getContextPathColumn()+" is null and "+
                                                                              getVirtualHostColumn()+" = ?");
                    statement.setString(1, rowId);
                    statement.setString(2, virtualHosts);

                    return statement;
                }
            }

            PreparedStatement statement = connection.prepareStatement("select * from "+getTableName()+
                                                                      " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, rowId);
            statement.setString(2, contextPath);
            statement.setString(3, virtualHosts);

            return statement;
        }
    }
    
    
    
    /**
     * SessionIdTableSchema
     *
     */
    public static class SessionIdTableSchema
    {
        protected DatabaseAdaptor _dbAdaptor;
        protected String _tableName = "JettySessionIds";
        protected String _idColumn = "id";

        public void setDatabaseAdaptor(DatabaseAdaptor dbAdaptor)
        {
            _dbAdaptor = dbAdaptor;
        }
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
        
        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }
    }


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
    public static class DatabaseAdaptor
    {
        String _dbName;
        boolean _isLower;
        boolean _isUpper;
        
        protected String _blobType; //if not set, is deduced from the type of the database at runtime
        protected String _longType; //if not set, is deduced from the type of the database at runtime


        public DatabaseAdaptor ()
        {           
        }
        
        
        public void adaptTo(DatabaseMetaData dbMeta)  
        throws SQLException
        {
            _dbName = dbMeta.getDatabaseProductName().toLowerCase(Locale.ENGLISH);
            if (LOG.isDebugEnabled())
                LOG.debug ("Using database {}",_dbName);
            _isLower = dbMeta.storesLowerCaseIdentifiers();
            _isUpper = dbMeta.storesUpperCaseIdentifiers(); 
        }
        
       
        public void setBlobType(String blobType)
        {
            _blobType = blobType;
        }
        
        public String getBlobType ()
        {
            if (_blobType != null)
                return _blobType;

            if (_dbName.startsWith("postgres"))
                return "bytea";

            return "blob";
        }
        

        public void setLongType(String longType)
        {
            _longType = longType;
        }
        

        public String getLongType ()
        {
            if (_longType != null)
                return _longType;

            if (_dbName == null)
                throw new IllegalStateException ("DbAdaptor missing metadata");
            
            if (_dbName.startsWith("oracle"))
                return "number(20)";

            return "bigint";
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
            if (_dbName == null)
                throw new IllegalStateException ("DbAdaptor missing metadata");
            
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


        public InputStream getBlobInputStream (ResultSet result, String columnName)
        throws SQLException
        {
            if (_dbName == null)
                throw new IllegalStateException ("DbAdaptor missing metadata");
            
            if (_dbName.startsWith("postgres"))
            {
                byte[] bytes = result.getBytes(columnName);
                return new ByteArrayInputStream(bytes);
            }

            Blob blob = result.getBlob(columnName);
            return blob.getBinaryStream();
        }


        public boolean isEmptyStringNull ()
        {
            if (_dbName == null)
                throw new IllegalStateException ("DbAdaptor missing metadata");
            
            return (_dbName.startsWith("oracle"));
        }
        
        /**
         * rowId is a reserved word for Oracle, so change the name of this column
         * @return true if db in use is oracle
         */
        public boolean isRowIdReserved ()
        {
            if (_dbName == null)
                throw new IllegalStateException ("DbAdaptor missing metadata");
            
            return (_dbName != null && _dbName.startsWith("oracle"));
        }
    }

    
    /**
     * Scavenger
     *
     */
    protected class Scavenger implements Runnable
    {

        @Override
        public void run()
        {
           try
           {
               scavenge();
           }
           finally
           {
               if (_scheduler != null && _scheduler.isRunning())
                   _scheduler.schedule(this, _scavengeIntervalMs, TimeUnit.MILLISECONDS);
           }
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

    /**
     * @param name
     * @deprecated see DbAdaptor.setBlobType
     */
    public void setBlobType (String name)
    {
        _dbAdaptor.setBlobType(name);
    }

    public DatabaseAdaptor getDbAdaptor()
    {
        return _dbAdaptor;
    }

    public void setDbAdaptor(DatabaseAdaptor dbAdaptor)
    {
        if (dbAdaptor == null)
            throw new IllegalStateException ("DbAdaptor cannot be null");
        
        _dbAdaptor = dbAdaptor;
    }

    /**
     * @return
     * @deprecated see DbAdaptor.getBlobType
     */
    public String getBlobType ()
    {
        return _dbAdaptor.getBlobType();
    }

    /**
     * @return
     * @deprecated see DbAdaptor.getLogType
     */
    public String getLongType()
    {
        return _dbAdaptor.getLongType();
    }

    /**
     * @param longType
     * @deprecated see DbAdaptor.setLongType
     */
    public void setLongType(String longType)
    {
       _dbAdaptor.setLongType(longType);
    }
    
    public SessionIdTableSchema getSessionIdTableSchema()
    {
        return _sessionIdTableSchema;
    }

    public void setSessionIdTableSchema(SessionIdTableSchema sessionIdTableSchema)
    {
        if (sessionIdTableSchema == null)
            throw new IllegalArgumentException("Null SessionIdTableSchema");
        
        _sessionIdTableSchema = sessionIdTableSchema;
    }

    public SessionTableSchema getSessionTableSchema()
    {
        return _sessionTableSchema;
    }

    public void setSessionTableSchema(SessionTableSchema sessionTableSchema)
    {
        _sessionTableSchema = sessionTableSchema;
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
        
        synchronized (this)
        {
            //if (_timer!=null && (period!=old_period || _task==null))
            if (_scheduler != null && (period!=old_period || _task==null))
            {
                if (_task!=null)
                    _task.cancel();
                if (_scavenger == null)
                    _scavenger = new Scavenger();
                _task = _scheduler.schedule(_scavenger,_scavengeIntervalMs,TimeUnit.MILLISECONDS);
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
        
         //try and use a common scheduler, fallback to own
         _scheduler =_server.getBean(Scheduler.class);
         if (_scheduler == null)
         {
             _scheduler = new ScheduledExecutorScheduler();
             _ownScheduler = true;
             _scheduler.start();
         }
  
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
            if (_ownScheduler && _scheduler !=null)
                _scheduler.stop();
            _scheduler=null;
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
        if (_sessionIdTableSchema == null)
            throw new IllegalStateException ("No SessionIdTableSchema");
        
        if (_sessionTableSchema == null)
            throw new IllegalStateException ("No SessionTableSchema");
        
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement())
        {
            //make the id table
            connection.setAutoCommit(true);
            DatabaseMetaData metaData = connection.getMetaData();
            _dbAdaptor.adaptTo(metaData);
            _sessionTableSchema.setDatabaseAdaptor(_dbAdaptor);
            _sessionIdTableSchema.setDatabaseAdaptor(_dbAdaptor);
            
            _createSessionIdTable = _sessionIdTableSchema.getCreateStatementAsString();
            _insertId = _sessionIdTableSchema.getInsertStatementAsString();
            _deleteId =  _sessionIdTableSchema.getDeleteStatementAsString();
            _queryId = _sessionIdTableSchema.getSelectStatementAsString();
            
            //checking for table existence is case-sensitive, but table creation is not
            String tableName = _dbAdaptor.convertIdentifier(_sessionIdTableSchema.getTableName());
            try (ResultSet result = metaData.getTables(null, null, tableName, null))
            {
                if (!result.next())
                {
                    //table does not exist, so create it
                    statement.executeUpdate(_createSessionIdTable);
                }
            }         
            
            //make the session table if necessary
            tableName = _dbAdaptor.convertIdentifier(_sessionTableSchema.getTableName());
            try (ResultSet result = metaData.getTables(null, null, tableName, null))
            {
                if (!result.next())
                {
                    //table does not exist, so create it
                    _createSessionTable = _sessionTableSchema.getCreateStatementAsString();
                    statement.executeUpdate(_createSessionTable);
                }
                else
                {
                    //session table exists, check it has maxinterval column
                    ResultSet colResult = null;
                    try
                    {
                        colResult = metaData.getColumns(null, null,
                                                        _dbAdaptor.convertIdentifier(_sessionTableSchema.getTableName()), 
                                                        _dbAdaptor.convertIdentifier(_sessionTableSchema.getMaxIntervalColumn()));
                    }
                    catch (SQLException s)
                    {
                        LOG.warn("Problem checking if "+_sessionTableSchema.getTableName()+
                                 " table contains "+_sessionTableSchema.getMaxIntervalColumn()+" column. Ensure table contains column definition: \""
                                +_sessionTableSchema.getMaxIntervalColumn()+" long not null default -999\"");
                        throw s;
                    }
                    try
                    {
                        if (!colResult.next())
                        {
                            try
                            {
                                //add the maxinterval column
                                statement.executeUpdate(_sessionTableSchema.getAlterTableForMaxIntervalAsString());
                            }
                            catch (SQLException s)
                            {
                                LOG.warn("Problem adding "+_sessionTableSchema.getMaxIntervalColumn()+
                                         " column. Ensure table contains column definition: \""+_sessionTableSchema.getMaxIntervalColumn()+
                                         " long not null default -999\"");
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
            String index1 = "idx_"+_sessionTableSchema.getTableName()+"_expiry";
            String index2 = "idx_"+_sessionTableSchema.getTableName()+"_session";

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
                statement.executeUpdate(_sessionTableSchema.getCreateIndexOverExpiryStatementAsString(index1));
            if (!index2Exists)
                statement.executeUpdate(_sessionTableSchema.getCreateIndexOverSessionStatementAsString(index2));

            //set up some strings representing the statements for session manipulation
            _insertSession = _sessionTableSchema.getInsertSessionStatementAsString();
            _deleteSession = _sessionTableSchema.getDeleteSessionStatementAsString();
            _updateSession = _sessionTableSchema.getUpdateSessionStatementAsString();
            _updateSessionNode = _sessionTableSchema.getUpdateSessionNodeStatementAsString();
            _updateSessionAccessTime = _sessionTableSchema.getUpdateSessionAccessTimeStatementAsString();
            _selectBoundedExpiredSessions = _sessionTableSchema.getBoundedExpiredSessionsStatementAsString();
            _selectExpiredSessions = _sessionTableSchema.getSelectExpiredSessionsStatementAsString();
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
                            String sessionId = result.getString(_sessionTableSchema.getIdColumn());
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
                                String sessionId = result.getString(_sessionTableSchema.getIdColumn());
                                String lastNode = result.getString(_sessionTableSchema.getLastNodeColumn());
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
                                String sessionId = result.getString(_sessionTableSchema.getIdColumn());
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

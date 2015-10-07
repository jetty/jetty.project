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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
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
import java.util.Map;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * JDBCSessionDataStore
 *
 *
 */
public class JDBCSessionDataStore extends AbstractSessionDataStore
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    
    
    protected int _deleteBlockSize = 10; //number of ids to include in where 'in' clause for finding long expired sessions
    protected boolean _initialized = false;
    protected long _lastScavengeTime = 0;



    private DatabaseAdaptor _dbAdaptor;



    private SessionTableSchema _sessionTableSchema;

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
        private String _driverClassName;
        private String _connectionUrl;
        private Driver _driver;
        private DataSource _datasource;
        private String _jndiName;


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
         * @param identifier the raw identifier
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

        /**
         * Configure jdbc connection information via a jdbc Driver
         *
         * @param driverClassName the driver classname
         * @param connectionUrl the driver connection url
         */
        public void setDriverInfo (String driverClassName, String connectionUrl)
        {
            _driverClassName=driverClassName;
            _connectionUrl=connectionUrl;
        }

        /**
         * Configure jdbc connection information via a jdbc Driver
         *
         * @param driverClass the driver class
         * @param connectionUrl the driver connection url
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
        
        public void setDatasourceName (String jndi)
        {
            _jndiName=jndi;
        }

        public void initializeDatabase ()
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
            {
                try
                {
                    InitialContext ic = new InitialContext();
                    _datasource = (DataSource)ic.lookup("jdbc/sessions"); //last ditch effort
                }
                catch (NamingException e)
                {
                    throw new IllegalStateException("No database configured for sessions");
                }
            }
        }

        
        /**
         * Get a connection from the driver or datasource.
         *
         * @return the connection for the datasource
         * @throws SQLException if unable to get the connection
         */
        protected Connection getConnection ()
        throws SQLException
        {
            if (_datasource != null)
                return _datasource.getConnection();
            else
                return DriverManager.getConnection(_connectionUrl);
        }
        
    }
    
    /**
     * SessionTableSchema
     *
     */
    public static class SessionTableSchema
    {      
        public final static int MAX_INTERVAL_NOT_SET = -999;
        
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
            String stem = "alter table "+getTableName()+" add "+getMaxIntervalColumn()+" "+longType;
            if (_dbAdaptor.getDBName().contains("oracle"))
                return stem + " default "+ MAX_INTERVAL_NOT_SET + " not null";
            else
                return stem +" not null default "+ MAX_INTERVAL_NOT_SET;
        }
        
        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }
        public String getInsertSessionStatementAsString()
        {
           return "insert into "+getTableName()+
            " ("+getIdColumn()+", "+getContextPathColumn()+", "+getVirtualHostColumn()+", "+getLastNodeColumn()+
            ", "+getAccessTimeColumn()+", "+getLastAccessTimeColumn()+", "+getCreateTimeColumn()+", "+getCookieTimeColumn()+
            ", "+getLastSavedTimeColumn()+", "+getExpiryTimeColumn()+", "+getMaxIntervalColumn()+", "+getMapColumn()+") "+
            " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        public String getUpdateSessionStatementAsString(SessionKey key)
        {
            String s =  "update "+getTableName()+
                    " set "+getIdColumn()+" = ?, "+getLastNodeColumn()+" = ?, "+getAccessTimeColumn()+" = ?, "+
                    getLastAccessTimeColumn()+" = ?, "+getLastSavedTimeColumn()+" = ?, "+getExpiryTimeColumn()+" = ?, "+
                    getMaxIntervalColumn()+" = ?, "+getMapColumn()+" = ? where ";

            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    return s+getIdColumn()+" = ? and "+
                            getContextPathColumn()+" is null and "+
                            getVirtualHostColumn()+" = ?";

                }
            }

            return s+getIdColumn()+" = ? and "+getContextPathColumn()+
                    " = ? and "+getVirtualHostColumn()+" = ?";
        }

        
        public String getBoundedExpiredSessionsStatementAsString()
        {
            return "select * from "+getTableName()+" where "+getLastNodeColumn()+" = ? and "+getExpiryTimeColumn()+" >= ? and "+getExpiryTimeColumn()+" <= ?";
        }
        
        public String getSelectExpiredSessionsStatementAsString()
        {
            return "select * from "+getTableName()+" where "+getExpiryTimeColumn()+" >0 and "+getExpiryTimeColumn()+" <= ?";
        }
     
        public PreparedStatement getLoadStatement (Connection connection, SessionKey key)
        throws SQLException
        { 
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");


            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement("select * from "+getTableName()+
                                                                              " where "+getIdColumn()+" = ? and "+
                                                                              getContextPathColumn()+" is null and "+
                                                                              getVirtualHostColumn()+" = ?");
                    statement.setString(1, key.getId());
                    statement.setString(2, key.getVhost());

                    return statement;
                }
            }

            PreparedStatement statement = connection.prepareStatement("select * from "+getTableName()+
                                                                      " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, key.getId());
            statement.setString(2, key.getCanonicalContextPath());
            statement.setString(3, key.getVhost());

            return statement;
        }

        
        
        public PreparedStatement getUpdateStatement (Connection connection, SessionKey key)
        throws SQLException
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String s = "update "+getTableName()+
                    " set "+getIdColumn()+" = ?, "+getLastNodeColumn()+" = ?, "+getAccessTimeColumn()+" = ?, "+
                    getLastAccessTimeColumn()+" = ?, "+getLastSavedTimeColumn()+" = ?, "+getExpiryTimeColumn()+" = ?, "+
                    getMaxIntervalColumn()+" = ?, "+getMapColumn()+" = ? where ";

            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement(s+getIdColumn()+" = ? and "+
                            getContextPathColumn()+" is null and "+
                            getVirtualHostColumn()+" = ?");
                    statement.setString(1, key.getId());
                    statement.setString(2, key.getVhost());
                    return statement;
                }
            }
            PreparedStatement statement = connection.prepareStatement(s+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, key.getId());
            statement.setString(2, key.getCanonicalContextPath());
            statement.setString(3, key.getVhost());

            return statement;
        }
        
        


        public PreparedStatement getDeleteStatement (Connection connection, SessionKey key)
        throws Exception
        { 
            if (_dbAdaptor == null)

                throw new IllegalStateException("No DB adaptor");


            if (key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath()))
            {
                if (_dbAdaptor.isEmptyStringNull())
                {
                    PreparedStatement statement = connection.prepareStatement("delete from "+getTableName()+
                                                                              " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                              " = ? and "+getVirtualHostColumn()+" = ?");
                    statement.setString(1, key.getId());
                    statement.setString(2, key.getVhost());
                    return statement;
                }
            }

            PreparedStatement statement = connection.prepareStatement("delete from "+getTableName()+
                                                                      " where "+getIdColumn()+" = ? and "+getContextPathColumn()+
                                                                      " = ? and "+getVirtualHostColumn()+" = ?");
            statement.setString(1, key.getId());
            statement.setString(2, key.getCanonicalContextPath());
            statement.setString(3, key.getVhost());

            return statement;

        }

        
        /**
         * Set up the tables in the database
         * @throws SQLException
         */
        /**
         * @throws SQLException
         */
        public void prepareTables()
        throws SQLException
        {
            try (Connection connection = _dbAdaptor.getConnection();
                 Statement statement = connection.createStatement())
            {
                //make the id table
                connection.setAutoCommit(true);
                DatabaseMetaData metaData = connection.getMetaData();
                _dbAdaptor.adaptTo(metaData);
                    
                
                //make the session table if necessary
                String tableName = _dbAdaptor.convertIdentifier(getTableName());
                try (ResultSet result = metaData.getTables(null, null, tableName, null))
                {
                    if (!result.next())
                    {
                        //table does not exist, so create it
                        statement.executeUpdate(getCreateStatementAsString());
                    }
                    else
                    {
                        //session table exists, check it has maxinterval column
                        ResultSet colResult = null;
                        try
                        {
                            colResult = metaData.getColumns(null, null,
                                                            _dbAdaptor.convertIdentifier(getTableName()), 
                                                            _dbAdaptor.convertIdentifier(getMaxIntervalColumn()));
                        }
                        catch (SQLException s)
                        {
                            LOG.warn("Problem checking if "+getTableName()+
                                     " table contains "+getMaxIntervalColumn()+" column. Ensure table contains column definition: \""
                                    + getMaxIntervalColumn()+" long not null default -999\"");
                            throw s;
                        }
                        try
                        {
                            if (!colResult.next())
                            {
                                try
                                {
                                    //add the maxinterval column
                                    statement.executeUpdate(getAlterTableForMaxIntervalAsString());
                                }
                                catch (SQLException s)
                                {
                                    LOG.warn("Problem adding "+getMaxIntervalColumn()+
                                             " column. Ensure table contains column definition: \""+getMaxIntervalColumn()+
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
                String index1 = "idx_"+getTableName()+"_expiry";
                String index2 = "idx_"+getTableName()+"_session";

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
                    statement.executeUpdate(getCreateIndexOverExpiryStatementAsString(index1));
                if (!index2Exists)
                    statement.executeUpdate(getCreateIndexOverSessionStatementAsString(index2));
            }
        }
    }
    
    
   
  
    public JDBCSessionDataStore ()
    {
        super ();
    }

    
    
    
    public void setDeleteBlockSize (int bsize)
    {
        this._deleteBlockSize = bsize;
    }

    public int getDeleteBlockSize ()
    {
        return this._deleteBlockSize;
    }
    

  

    @Override
    public SessionData newSessionData(String id, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        return new SessionData(id, created, accessed, lastAccessed, maxInactiveMs);
    }



    @Override
    protected void doStart() throws Exception
    {
        initialize();
        super.doStart();
    }




    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }




    public void initialize () throws Exception
    {
        if (!_initialized)
        {
            _initialized = true;
            
            if (_dbAdaptor == null)
                _dbAdaptor = new DatabaseAdaptor();

            if (_sessionTableSchema == null)
                _sessionTableSchema = new SessionTableSchema();
            
            _dbAdaptor.initializeDatabase();
            _sessionTableSchema.setDatabaseAdaptor(_dbAdaptor);
            _sessionTableSchema.prepareTables();
        }

    }


 
    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#load(org.eclipse.jetty.server.session.x.SessionKey)
     */
    @Override
    public SessionData load(SessionKey key) throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection();
                PreparedStatement statement = _sessionTableSchema.getLoadStatement(connection, key);
                ResultSet result = statement.executeQuery())
        {
            SessionData data = null;
            if (result.next())
            {                    
                data = newSessionData(key.getId(), 
                                      result.getLong(_sessionTableSchema.getCreateTimeColumn()), 
                                      result.getLong(_sessionTableSchema.getAccessTimeColumn()), 
                                      result.getLong(_sessionTableSchema.getLastAccessTimeColumn()), 
                                      result.getLong(_sessionTableSchema.getMaxIntervalColumn()));
                data.setCookieSet(result.getLong(_sessionTableSchema.getCookieTimeColumn()));
                data.setLastNode(result.getString(_sessionTableSchema.getLastNodeColumn()));
                data.setLastSaved(result.getLong(_sessionTableSchema.getLastSavedTimeColumn()));
                data.setExpiry(result.getLong(_sessionTableSchema.getExpiryTimeColumn()));
                data.setContextPath(result.getString(_sessionTableSchema.getContextPathColumn())); //TODO needed? this is part of the key now
                data.setVhost(result.getString(_sessionTableSchema.getVirtualHostColumn())); //TODO needed??? this is part of the key now

                try (InputStream is = _dbAdaptor.getBlobInputStream(result, _sessionTableSchema.getMapColumn());
                        ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(is))
                {
                    Object o = ois.readObject();
                    data.putAllAttributes((Map<String,Object>)o);
                }
                catch (Exception e)
                {
                    throw new UnreadableSessionDataException (key, e);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("LOADED session "+data);
            }
            else
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed to load session "+key.getId());
            return data;
        }
    }



    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#delete(java.lang.String)
     */
    @Override
    public boolean delete(SessionKey key) throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = _sessionTableSchema.getDeleteStatement(connection, key))
        {
            connection.setAutoCommit(true);
            int rows = statement.executeUpdate();
            if (LOG.isDebugEnabled())
                LOG.debug("Deleted Session {}:{}",key,(rows>0));
            return rows > 0;
        }
    }




    /** 
     * @see org.eclipse.jetty.server.session.x.AbstractSessionDataStore#doStore()
     */
    @Override
    public void doStore(SessionKey key, SessionData data) throws Exception
    {
        if (data==null || key==null)
            return;

        try (Connection connection = _dbAdaptor.getConnection())        
        {
            connection.setAutoCommit(true);
            
            //If last saved field not set, then this is a fresh session that has never been persisted
            if (data.getLastSaved() <= 0)
            {     
                doInsert(connection, key, data);
            }
            else
            {
                doUpdate(connection, key, data);            
            }
         
        }

    }


    private void doInsert (Connection connection, SessionKey key, SessionData data) 
    throws Exception
    {
        String s = _sessionTableSchema.getInsertSessionStatementAsString();

        try  (PreparedStatement statement = connection.prepareStatement(s))
        {

            long now = System.currentTimeMillis();


            statement.setString(1, key.getId()); //session id
            statement.setString(2, key.getCanonicalContextPath()); //context path
            statement.setString(3, key.getVhost()); //first vhost
            statement.setString(4, data.getLastNode());//my node id
            statement.setLong(5, data.getAccessed());//accessTime
            statement.setLong(6, data.getLastAccessed()); //lastAccessTime
            statement.setLong(7, data.getCreated()); //time created
            statement.setLong(8, data.getCookieSet());//time cookie was set
            statement.setLong(9, now); //last saved time
            statement.setLong(10, data.getExpiry());
            statement.setLong(11, data.getMaxInactiveMs());

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(data.getAllAttributes());
            oos.flush();
            byte[] bytes = baos.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            statement.setBinaryStream(12, bais, bytes.length);//attribute map as blob
            statement.executeUpdate();
            data.setLastSaved(now);
            if (LOG.isDebugEnabled())
                LOG.debug("Inserted session "+data);
        }
    }

    private void doUpdate (Connection connection, SessionKey key, SessionData data)
    throws Exception
    {
       try (PreparedStatement statement = connection.prepareStatement(_sessionTableSchema.getUpdateSessionStatementAsString(key)))
       {

           long now = System.currentTimeMillis();
           
  
           statement.setString(1, data.getLastNode());//should be my node id
           statement.setLong(2, data.getAccessed());//accessTime
           statement.setLong(3, data.getLastAccessed()); //lastAccessTime
           statement.setLong(4, now); //last saved time
           statement.setLong(5, data.getExpiry());
           statement.setLong(6, data.getMaxInactiveMs());

           ByteArrayOutputStream baos = new ByteArrayOutputStream();
           ObjectOutputStream oos = new ObjectOutputStream(baos);
           oos.writeObject(data.getAllAttributes());
           oos.flush();
           byte[] bytes = baos.toByteArray();
           ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
           statement.setBinaryStream(7, bais, bytes.length);//attribute map as blob

           if ((key.getCanonicalContextPath() == null || "".equals(key.getCanonicalContextPath())) && _dbAdaptor.isEmptyStringNull())
           {
               statement.setString(8, key.getId());
               statement.setString(9, key.getVhost()); 
           }
           else
           {
               statement.setString(8, key.getId());
               statement.setString(9, key.getCanonicalContextPath());
               statement.setString(10, key.getVhost());
           }
           
           statement.executeUpdate();

           data.setLastSaved(now);
           if (LOG.isDebugEnabled())
               LOG.debug("Updated session "+data);
       }
    }


    /** 
     * @see org.eclipse.jetty.server.session.x.SessionDataStore#scavenge()
     */
    @Override
    public void scavenge()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Scavenge sweep started at "+System.currentTimeMillis());

        long now = System.currentTimeMillis();
        
        //first time we're called, don't scavenge
        if (_lastScavengeTime == 0)
        {
            _lastScavengeTime = now;
            return;
        }

        
        /*TODO
         * 1. Select sessions for our node and our context that have expired since our last pass, giving some leeway
         * 2. Select sessions for our node that have expired some time ago
         * 3. Select sessions for any node that have expired quite a while ago
         */
       
    }
    
    
    public void setDatabaseAdaptor (DatabaseAdaptor dbAdaptor)
    {
        _dbAdaptor = dbAdaptor;
    }
    
    public void setSessionTableSchema (SessionTableSchema schema)
    {
        _sessionTableSchema = schema;
    }

}









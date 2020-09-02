//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * JDBCSessionDataStore
 *
 * Session data stored in database
 */
@ManagedObject
public class JDBCSessionDataStore extends AbstractSessionDataStore
{
    static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    /**
     * Used for Oracle and other databases where "" is treated as NULL
     */
    public static final String NULL_CONTEXT_PATH = "/";

    protected boolean _initialized = false;
    protected DatabaseAdaptor _dbAdaptor;
    protected SessionTableSchema _sessionTableSchema;
    protected boolean _schemaProvided;

    private static final ByteArrayInputStream EMPTY = new ByteArrayInputStream(new byte[0]);

    /**
     * SessionTableSchema
     */
    public static class SessionTableSchema
    {
        public static final int MAX_INTERVAL_NOT_SET = -999;
        public static final String INFERRED = "INFERRED";

        protected DatabaseAdaptor _dbAdaptor;
        protected String _schemaName = null;
        protected String _catalogName = null;
        protected String _tableName = "JettySessions";
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
        
        public void setCatalogName(String catalogName)
        {
            if (catalogName != null && StringUtil.isBlank(catalogName))
                _catalogName = null;
            else
                _catalogName = catalogName;
        }

        public String getCatalogName()
        {
            return _catalogName;
        }
        
        public String getSchemaName()
        {
            return _schemaName;
        }

        public void setSchemaName(String schemaName)
        {
            if (schemaName != null && StringUtil.isBlank(schemaName))
                _schemaName = null;
            else
                _schemaName = schemaName;
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

        private String getSchemaTableName()
        {
            return (getSchemaName() != null ? getSchemaName() + "." : "") + getTableName();
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

        public String getCreateStatementAsString()
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DBAdaptor");

            String blobType = _dbAdaptor.getBlobType();
            String longType = _dbAdaptor.getLongType();
            String stringType = _dbAdaptor.getStringType();

            return "create table " + _tableName + " (" + _idColumn + " " + stringType + "(120), " +
                _contextPathColumn + " " + stringType + "(60), " + _virtualHostColumn + " " + stringType + "(60), " + _lastNodeColumn + " " + stringType + "(60), " + _accessTimeColumn + " " + longType + ", " +
                _lastAccessTimeColumn + " " + longType + ", " + _createTimeColumn + " " + longType + ", " + _cookieTimeColumn + " " + longType + ", " +
                _lastSavedTimeColumn + " " + longType + ", " + _expiryTimeColumn + " " + longType + ", " + _maxIntervalColumn + " " + longType + ", " +
                _mapColumn + " " + blobType + ", primary key(" + _idColumn + ", " + _contextPathColumn + "," + _virtualHostColumn + "))";
        }

        public String getCreateIndexOverExpiryStatementAsString(String indexName)
        {
            return "create index " + indexName + " on " + getSchemaTableName() + " (" + getExpiryTimeColumn() + ")";
        }

        public String getCreateIndexOverSessionStatementAsString(String indexName)
        {
            return "create index " + indexName + " on " + getSchemaTableName() + " (" + getIdColumn() + ", " + getContextPathColumn() + ")";
        }

        public String getAlterTableForMaxIntervalAsString()
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DBAdaptor");
            String longType = _dbAdaptor.getLongType();
            String stem = "alter table " + getSchemaTableName() + " add " + getMaxIntervalColumn() + " " + longType;
            if (_dbAdaptor.getDBName().contains("oracle"))
                return stem + " default " + MAX_INTERVAL_NOT_SET + " not null";
            else
                return stem + " not null default " + MAX_INTERVAL_NOT_SET;
        }

        private void checkNotNull(String s)
        {
            if (s == null)
                throw new IllegalArgumentException(s);
        }

        public String getInsertSessionStatementAsString()
        {
            return "insert into " + getSchemaTableName() +
                " (" + getIdColumn() + ", " + getContextPathColumn() + ", " + getVirtualHostColumn() + ", " + getLastNodeColumn() +
                ", " + getAccessTimeColumn() + ", " + getLastAccessTimeColumn() + ", " + getCreateTimeColumn() + ", " + getCookieTimeColumn() +
                ", " + getLastSavedTimeColumn() + ", " + getExpiryTimeColumn() + ", " + getMaxIntervalColumn() + ", " + getMapColumn() + ") " +
                " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        public PreparedStatement getUpdateSessionStatement(Connection connection, String id, SessionContext context)
            throws SQLException
        {
            String s = "update " + getSchemaTableName() +
                " set " + getLastNodeColumn() + " = ?, " + getAccessTimeColumn() + " = ?, " +
                getLastAccessTimeColumn() + " = ?, " + getLastSavedTimeColumn() + " = ?, " + getExpiryTimeColumn() + " = ?, " +
                getMaxIntervalColumn() + " = ?, " + getMapColumn() + " = ? where " + getIdColumn() + " = ? and " + getContextPathColumn() +
                " = ? and " + getVirtualHostColumn() + " = ?";

            String cp = context.getCanonicalContextPath();
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            PreparedStatement statement = connection.prepareStatement(s);
            statement.setString(8, id);
            statement.setString(9, cp);
            statement.setString(10, context.getVhost());
            return statement;
        }

        public PreparedStatement getExpiredSessionsStatement(Connection connection, String canonicalContextPath, String vhost, long expiry)
            throws SQLException
        {
            // TODO expiry should be a delay rather than an absolute time.

            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String cp = canonicalContextPath;
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            PreparedStatement statement = connection.prepareStatement("select " + getIdColumn() + ", " + getExpiryTimeColumn() +
                " from " + getSchemaTableName() + " where " + getContextPathColumn() + " = ? and " +
                getVirtualHostColumn() + " = ? and " +
                getExpiryTimeColumn() + " >0 and " + getExpiryTimeColumn() + " <= ?");

            statement.setString(1, cp);
            statement.setString(2, vhost);
            statement.setLong(3, expiry);
            return statement;
        }

        public PreparedStatement getMyExpiredSessionsStatement(Connection connection, SessionContext sessionContext, long expiry)
            throws SQLException
        {
            // TODO expiry should be a delay rather than an absolute time.

            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String cp = sessionContext.getCanonicalContextPath();
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            PreparedStatement statement = connection.prepareStatement("select " + getIdColumn() + ", " + getExpiryTimeColumn() +
                " from " + getSchemaTableName() + " where " +
                getLastNodeColumn() + " = ? and " +
                getContextPathColumn() + " = ? and " +
                getVirtualHostColumn() + " = ? and " +
                getExpiryTimeColumn() + " >0 and " + getExpiryTimeColumn() + " <= ?");

            statement.setString(1, sessionContext.getWorkerName());
            statement.setString(2, cp);
            statement.setString(3, sessionContext.getVhost());
            statement.setLong(4, expiry);
            return statement;
        }

        public PreparedStatement getAllAncientExpiredSessionsStatement(Connection connection)
            throws SQLException
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            PreparedStatement statement = connection.prepareStatement("select " + getIdColumn() + ", " + getContextPathColumn() + ", " + getVirtualHostColumn() +
                " from " + getSchemaTableName() +
                " where " + getExpiryTimeColumn() + " >0 and " + getExpiryTimeColumn() + " <= ?");
            return statement;
        }

        public PreparedStatement getCheckSessionExistsStatement(Connection connection, SessionContext context)
            throws SQLException
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String cp = context.getCanonicalContextPath();
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            PreparedStatement statement = connection.prepareStatement("select " + getIdColumn() + ", " + getExpiryTimeColumn() +
                " from " + getSchemaTableName() +
                " where " + getIdColumn() + " = ? and " +
                getContextPathColumn() + " = ? and " +
                getVirtualHostColumn() + " = ?");
            statement.setString(2, cp);
            statement.setString(3, context.getVhost());
            return statement;
        }

        public PreparedStatement getLoadStatement(Connection connection, String id, SessionContext contextId)
            throws SQLException
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String cp = contextId.getCanonicalContextPath();
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            PreparedStatement statement = connection.prepareStatement("select * from " + getSchemaTableName() +
                " where " + getIdColumn() + " = ? and " + getContextPathColumn() +
                " = ? and " + getVirtualHostColumn() + " = ?");
            statement.setString(1, id);
            statement.setString(2, cp);
            statement.setString(3, contextId.getVhost());

            return statement;
        }

        public PreparedStatement getUpdateStatement(Connection connection, String id, SessionContext contextId)
            throws SQLException
        {
            if (_dbAdaptor == null)
                throw new IllegalStateException("No DB adaptor");

            String cp = contextId.getCanonicalContextPath();
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            String s = "update " + getSchemaTableName() +
                " set " + getLastNodeColumn() + " = ?, " + getAccessTimeColumn() + " = ?, " +
                getLastAccessTimeColumn() + " = ?, " + getLastSavedTimeColumn() + " = ?, " + getExpiryTimeColumn() + " = ?, " +
                getMaxIntervalColumn() + " = ?, " + getMapColumn() + " = ? where " + getIdColumn() + " = ? and " + getContextPathColumn() +
                " = ? and " + getVirtualHostColumn() + " = ?";

            PreparedStatement statement = connection.prepareStatement(s);
            statement.setString(8, id);
            statement.setString(9, cp);
            statement.setString(10, contextId.getVhost());

            return statement;
        }

        public PreparedStatement getDeleteStatement(Connection connection, String id, SessionContext contextId)
            throws Exception
        {
            if (_dbAdaptor == null)

                throw new IllegalStateException("No DB adaptor");

            String cp = contextId.getCanonicalContextPath();
            if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                cp = NULL_CONTEXT_PATH;

            PreparedStatement statement = connection.prepareStatement("delete from " + getSchemaTableName() +
                " where " + getIdColumn() + " = ? and " + getContextPathColumn() +
                " = ? and " + getVirtualHostColumn() + " = ?");
            statement.setString(1, id);
            statement.setString(2, cp);
            statement.setString(3, contextId.getVhost());

            return statement;
        }

        /**
         * Set up the tables in the database
         *
         * @throws SQLException if unable to prepare tables
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
                
                String schemaName = _dbAdaptor.convertIdentifier(getSchemaName());
                if (INFERRED.equalsIgnoreCase(schemaName))
                {
                    //use the value from the connection -
                    //NOTE that this value will also now be prepended to ALL
                    //table names in queries/updates.
                    schemaName = connection.getSchema();
                    setSchemaName(schemaName);
                }
                String catalogName = _dbAdaptor.convertIdentifier(getCatalogName());
                if (INFERRED.equalsIgnoreCase(catalogName))
                {
                    //use the value from the connection
                    catalogName = connection.getCatalog();
                    setCatalogName(catalogName);
                }
                
                try (ResultSet result = metaData.getTables(catalogName, schemaName, tableName, null))
                {
                    if (!result.next())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Creating table {} schema={} catalog={}", tableName, schemaName, catalogName);
                        //table does not exist, so create it
                        statement.executeUpdate(getCreateStatementAsString());
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Not creating table {} schema={} catalog={}", tableName, schemaName, catalogName);
                        //session table exists, check it has maxinterval column
                        ResultSet colResult = null;
                        try
                        {
                            colResult = metaData.getColumns(catalogName, schemaName, tableName,
                                _dbAdaptor.convertIdentifier(getMaxIntervalColumn()));
                        }
                        catch (SQLException sqlEx)
                        {
                            LOG.warn("Problem checking if {} table contains {} column. Ensure table contains column with definition: long not null default -999",
                                getTableName(), getMaxIntervalColumn());
                            throw sqlEx;
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
                                catch (SQLException sqlEx)
                                {
                                    LOG.warn("Problem adding {} column. Ensure table contains column definition: long not null default -999", getMaxIntervalColumn());
                                    throw sqlEx;
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
                String index1 = "idx_" + getTableName() + "_expiry";
                String index2 = "idx_" + getTableName() + "_session";

                boolean index1Exists = false;
                boolean index2Exists = false;
                try (ResultSet result = metaData.getIndexInfo(catalogName, schemaName, tableName, false, true))
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

        @Override
        public String toString()
        {
            return String.format("%s[%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s]", super.toString(),
                _catalogName, _schemaName, _tableName, _idColumn, _contextPathColumn, _virtualHostColumn, _cookieTimeColumn, _createTimeColumn,
                _expiryTimeColumn, _accessTimeColumn, _lastAccessTimeColumn, _lastNodeColumn, _lastSavedTimeColumn, _maxIntervalColumn);
        }
    }

    public JDBCSessionDataStore()
    {
        super();
    }

    @Override
    protected void doStart() throws Exception
    {
        if (_dbAdaptor == null)
            throw new IllegalStateException("No jdbc config");

        initialize();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _initialized = false;
        if (!_schemaProvided)
            _sessionTableSchema = null;
    }

    public void initialize() throws Exception
    {
        if (!_initialized)
        {
            _initialized = true;

            //taking the defaults if one not set
            if (_sessionTableSchema == null)
            {
                _sessionTableSchema = new SessionTableSchema();
                addBean(_sessionTableSchema, true);
            }

            _dbAdaptor.initialize();
            _sessionTableSchema.setDatabaseAdaptor(_dbAdaptor);
            _sessionTableSchema.prepareTables();
        }
    }

    @Override
    public SessionData doLoad(String id) throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = _sessionTableSchema.getLoadStatement(connection, id, _context);
             ResultSet result = statement.executeQuery())
        {
            SessionData data = null;
            if (result.next())
            {
                data = newSessionData(id,
                    result.getLong(_sessionTableSchema.getCreateTimeColumn()),
                    result.getLong(_sessionTableSchema.getAccessTimeColumn()),
                    result.getLong(_sessionTableSchema.getLastAccessTimeColumn()),
                    result.getLong(_sessionTableSchema.getMaxIntervalColumn()));
                data.setCookieSet(result.getLong(_sessionTableSchema.getCookieTimeColumn()));
                data.setLastNode(result.getString(_sessionTableSchema.getLastNodeColumn()));
                data.setLastSaved(result.getLong(_sessionTableSchema.getLastSavedTimeColumn()));
                data.setExpiry(result.getLong(_sessionTableSchema.getExpiryTimeColumn()));
                data.setContextPath(_context.getCanonicalContextPath());
                data.setVhost(_context.getVhost());

                try (InputStream is = _dbAdaptor.getBlobInputStream(result, _sessionTableSchema.getMapColumn());
                     ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(is))
                {
                    SessionData.deserializeAttributes(data, ois);
                }
                catch (Exception e)
                {
                    throw new UnreadableSessionDataException(id, _context, e);
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("LOADED session {}", data);
            }
            else if (LOG.isDebugEnabled())
                LOG.debug("No session {}", id);

            return data;
        }
    }

    @Override
    public boolean delete(String id) throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection();
             PreparedStatement statement = _sessionTableSchema.getDeleteStatement(connection, id, _context))
        {
            connection.setAutoCommit(true);
            int rows = statement.executeUpdate();
            if (LOG.isDebugEnabled())
                LOG.debug("Deleted Session {}:{}", id, (rows > 0));

            return rows > 0;
        }
    }

    @Override
    public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
    {
        if (data == null || id == null)
            return;

        if (lastSaveTime <= 0)
        {
            doInsert(id, data);
        }
        else
        {
            doUpdate(id, data);
        }
    }

    protected void doInsert(String id, SessionData data)
        throws Exception
    {
        String s = _sessionTableSchema.getInsertSessionStatementAsString();

        try (Connection connection = _dbAdaptor.getConnection())
        {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(s))
            {
                statement.setString(1, id); //session id

                String cp = _context.getCanonicalContextPath();
                if (_dbAdaptor.isEmptyStringNull() && StringUtil.isBlank(cp))
                    cp = NULL_CONTEXT_PATH;

                statement.setString(2, cp); //context path

                statement.setString(3, _context.getVhost()); //first vhost
                statement.setString(4, data.getLastNode());//my node id
                statement.setLong(5, data.getAccessed());//accessTime
                statement.setLong(6, data.getLastAccessed()); //lastAccessTime
                statement.setLong(7, data.getCreated()); //time created
                statement.setLong(8, data.getCookieSet());//time cookie was set
                statement.setLong(9, data.getLastSaved()); //last saved time
                statement.setLong(10, data.getExpiry());
                statement.setLong(11, data.getMaxInactiveMs());

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos))
                {
                    SessionData.serializeAttributes(data, oos);
                    byte[] bytes = baos.toByteArray();
                    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                    statement.setBinaryStream(12, bais, bytes.length);//attribute map as blob
                }

                statement.executeUpdate();
                if (LOG.isDebugEnabled())
                    LOG.debug("Inserted session " + data);
            }
        }
    }

    protected void doUpdate(String id, SessionData data)
        throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection())
        {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = _sessionTableSchema.getUpdateSessionStatement(connection, data.getId(), _context))
            {
                statement.setString(1, data.getLastNode());//should be my node id
                statement.setLong(2, data.getAccessed());//accessTime
                statement.setLong(3, data.getLastAccessed()); //lastAccessTime
                statement.setLong(4, data.getLastSaved()); //last saved time
                statement.setLong(5, data.getExpiry());
                statement.setLong(6, data.getMaxInactiveMs());

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos))
                {
                    SessionData.serializeAttributes(data, oos);
                    byte[] bytes = baos.toByteArray();
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes))
                    {
                        statement.setBinaryStream(7, bais, bytes.length);//attribute map as blob
                    }
                }

                statement.executeUpdate();

                if (LOG.isDebugEnabled())
                    LOG.debug("Updated session " + data);
            }
        }
    }

    @Override
    public Set<String> doGetExpired(Set<String> candidates)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Getting expired sessions at time {}", System.currentTimeMillis());

        long now = System.currentTimeMillis();

        Set<String> expiredSessionKeys = new HashSet<>();
        try (Connection connection = _dbAdaptor.getConnection())
        {
            connection.setAutoCommit(true);

            /*
             * 1. Select sessions managed by this node for our context that have expired
             */
            long upperBound = now;
            if (LOG.isDebugEnabled())
                LOG.debug("{}- Pass 1: Searching for sessions for context {} managed by me and expired before {}", _context.getWorkerName(), _context.getCanonicalContextPath(), upperBound);

            try (PreparedStatement statement = _sessionTableSchema.getExpiredSessionsStatement(connection, _context.getCanonicalContextPath(), _context.getVhost(), upperBound))
            {
                try (ResultSet result = statement.executeQuery())
                {
                    while (result.next())
                    {
                        String sessionId = result.getString(_sessionTableSchema.getIdColumn());
                        long exp = result.getLong(_sessionTableSchema.getExpiryTimeColumn());
                        expiredSessionKeys.add(sessionId);
                        if (LOG.isDebugEnabled())
                            LOG.debug(_context.getCanonicalContextPath() + "- Found expired sessionId=" + sessionId);
                    }
                }
            }

            /*
             *  2. Select sessions for any node or context that have expired
             *  at least 1 graceperiod since the last expiry check. If we haven't done previous expiry checks, then check
             *  those that have expired at least 3 graceperiod ago.
             */
            try (PreparedStatement selectExpiredSessions = _sessionTableSchema.getAllAncientExpiredSessionsStatement(connection))
            {
                if (_lastExpiryCheckTime <= 0)
                    upperBound = (now - (3 * (1000L * _gracePeriodSec)));
                else
                    upperBound = _lastExpiryCheckTime - (1000L * _gracePeriodSec);

                if (LOG.isDebugEnabled())
                    LOG.debug("{}- Pass 2: Searching for sessions expired before {}", _context.getWorkerName(), upperBound);

                selectExpiredSessions.setLong(1, upperBound);
                try (ResultSet result = selectExpiredSessions.executeQuery())
                {
                    while (result.next())
                    {
                        String sessionId = result.getString(_sessionTableSchema.getIdColumn());
                        String ctxtpth = result.getString(_sessionTableSchema.getContextPathColumn());
                        String vh = result.getString(_sessionTableSchema.getVirtualHostColumn());
                        expiredSessionKeys.add(sessionId);
                        if (LOG.isDebugEnabled())
                            LOG.debug("{}- Found expired sessionId=", _context.getWorkerName(), sessionId);
                    }
                }
            }

            Set<String> notExpiredInDB = new HashSet<>();
            for (String k : candidates)
            {
                //there are some keys that the session store thought had expired, but were not
                //found in our sweep either because it is no longer in the db, or its
                //expiry time was updated
                if (!expiredSessionKeys.contains(k))
                    notExpiredInDB.add(k);
            }

            if (!notExpiredInDB.isEmpty())
            {
                //we have some sessions to check
                try (PreparedStatement checkSessionExists = _sessionTableSchema.getCheckSessionExistsStatement(connection, _context))
                {
                    for (String k : notExpiredInDB)
                    {
                        checkSessionExists.setString(1, k);
                        try (ResultSet result = checkSessionExists.executeQuery())
                        {
                            if (!result.next())
                            {
                                //session doesn't exist any more, can be expired
                                expiredSessionKeys.add(k);
                            }
                            //else its expiry time has not been reached
                        }
                        catch (Exception e)
                        {
                            LOG.warn("{} Problem checking if potentially expired session {} exists in db", _context.getWorkerName(), k);
                            LOG.warn(e);
                        }
                    }
                }
            }

            return expiredSessionKeys;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return expiredSessionKeys; //return whatever we got
        }
    }

    public void setDatabaseAdaptor(DatabaseAdaptor dbAdaptor)
    {
        checkStarted();
        updateBean(_dbAdaptor, dbAdaptor);
        _dbAdaptor = dbAdaptor;
    }

    public void setSessionTableSchema(SessionTableSchema schema)
    {
        checkStarted();
        updateBean(_sessionTableSchema, schema);
        _sessionTableSchema = schema;
        _schemaProvided = true;
    }

    @Override
    @ManagedAttribute(value = "does this store serialize sessions", readonly = true)
    public boolean isPassivating()
    {
        return true;
    }

    @Override
    public boolean exists(String id)
        throws Exception
    {
        try (Connection connection = _dbAdaptor.getConnection())
        {
            connection.setAutoCommit(true);

            //non-expired session exists?
            try (PreparedStatement checkSessionExists = _sessionTableSchema.getCheckSessionExistsStatement(connection, _context))
            {
                checkSessionExists.setString(1, id);
                try (ResultSet result = checkSessionExists.executeQuery())
                {
                    if (!result.next())
                    {
                        return false; //no such session
                    }
                    else
                    {
                        long expiry = result.getLong(_sessionTableSchema.getExpiryTimeColumn());
                        if (expiry <= 0) //never expires
                            return true;
                        else
                            return (expiry > System.currentTimeMillis()); //hasn't already expired
                    }
                }
            }
        }
    }
}

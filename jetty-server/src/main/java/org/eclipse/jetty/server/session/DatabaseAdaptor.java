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
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * DatabaseAdaptor
 *
 * Handles differences between databases.
 *
 * Postgres uses the getBytes and setBinaryStream methods to access
 * a "bytea" datatype, which can be up to 1Gb of binary data. MySQL
 * is happy to use the "blob" type and getBlob() methods instead.
 */
public class DatabaseAdaptor
{
    static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");

    String _dbName;
    boolean _isLower;
    boolean _isUpper;

    protected String _blobType; //if not set, is deduced from the type of the database at runtime
    protected String _longType; //if not set, is deduced from the type of the database at runtime
    protected String _stringType; //if not set defaults to 'varchar'
    private String _driverClassName;
    private String _connectionUrl;
    private Driver _driver;
    private DataSource _datasource;

    private String _jndiName;

    public DatabaseAdaptor()
    {
    }

    public void adaptTo(DatabaseMetaData dbMeta)
        throws SQLException
    {
        _dbName = dbMeta.getDatabaseProductName().toLowerCase(Locale.ENGLISH);
        if (LOG.isDebugEnabled())
            LOG.debug("Using database {}", _dbName);
        _isLower = dbMeta.storesLowerCaseIdentifiers();
        _isUpper = dbMeta.storesUpperCaseIdentifiers();
    }

    public void setBlobType(String blobType)
    {
        _blobType = blobType;
    }

    public String getBlobType()
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

    public String getLongType()
    {
        if (_longType != null)
            return _longType;

        if (_dbName == null)
            throw new IllegalStateException("DbAdaptor missing metadata");

        if (_dbName.startsWith("oracle"))
            return "number(20)";

        return "bigint";
    }

    public void setStringType(String stringType)
    {
        _stringType = stringType;
    }

    public String getStringType()
    {
        if (_stringType != null)
            return _stringType;

        return "varchar";
    }

    /**
     * Convert a camel case identifier into either upper or lower
     * depending on the way the db stores identifiers.
     *
     * @param identifier the raw identifier
     * @return the converted identifier
     */
    public String convertIdentifier(String identifier)
    {
        if (identifier == null)
            return null;

        if (_dbName == null)
            throw new IllegalStateException("DbAdaptor missing metadata");

        if (_isLower)
            return identifier.toLowerCase(Locale.ENGLISH);
        if (_isUpper)
            return identifier.toUpperCase(Locale.ENGLISH);

        return identifier;
    }

    public String getDBName()
    {
        return _dbName;
    }

    public InputStream getBlobInputStream(ResultSet result, String columnName)
        throws SQLException
    {
        if (_dbName == null)
            throw new IllegalStateException("DbAdaptor missing metadata");

        if (_dbName.startsWith("postgres"))
        {
            byte[] bytes = result.getBytes(columnName);
            return new ByteArrayInputStream(bytes);
        }

        try
        {
            Blob blob = result.getBlob(columnName);
            return blob.getBinaryStream();
        }
        catch (SQLFeatureNotSupportedException ex)
        {
            byte[] bytes = result.getBytes(columnName);
            return new ByteArrayInputStream(bytes);
        }
    }

    public boolean isEmptyStringNull()
    {
        if (_dbName == null)
            throw new IllegalStateException("DbAdaptor missing metadata");

        return (_dbName.startsWith("oracle"));
    }

    /**
     * rowId is a reserved word for Oracle, so change the name of this column
     *
     * @return true if db in use is oracle
     */
    public boolean isRowIdReserved()
    {
        if (_dbName == null)
            throw new IllegalStateException("DbAdaptor missing metadata");

        return (_dbName != null && _dbName.startsWith("oracle"));
    }

    /**
     * Configure jdbc connection information via a jdbc Driver
     *
     * @param driverClassName the driver classname
     * @param connectionUrl the driver connection url
     */
    public void setDriverInfo(String driverClassName, String connectionUrl)
    {
        _driverClassName = driverClassName;
        _connectionUrl = connectionUrl;
    }

    /**
     * Configure jdbc connection information via a jdbc Driver
     *
     * @param driverClass the driver class
     * @param connectionUrl the driver connection url
     */
    public void setDriverInfo(Driver driverClass, String connectionUrl)
    {
        _driver = driverClass;
        _connectionUrl = connectionUrl;
    }

    public void setDatasource(DataSource ds)
    {
        _datasource = ds;
    }

    public void setDatasourceName(String jndi)
    {
        _jndiName = jndi;
    }

    public String getDatasourceName()
    {
        return _jndiName;
    }

    public DataSource getDatasource()
    {
        return _datasource;
    }

    public String getDriverClassName()
    {
        return _driverClassName;
    }

    public Driver getDriver()
    {
        return _driver;
    }

    public String getConnectionUrl()
    {
        return _connectionUrl;
    }

    public void initialize()
        throws Exception
    {
        if (_datasource != null)
            return; //already set up

        if (_jndiName != null)
        {
            InitialContext ic = new InitialContext();
            _datasource = (DataSource)ic.lookup(_jndiName);
        }
        else if (_driver != null && _connectionUrl != null)
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
    protected Connection getConnection()
        throws SQLException
    {
        if (_datasource != null)
            return _datasource.getConnection();
        else
            return DriverManager.getConnection(_connectionUrl);
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
        return String.format("%s[jndi=%s,driver=%s]", super.toString(), _jndiName, _driverClassName);
    }
}

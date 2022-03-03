//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.plus.security;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.eclipse.jetty.ee9.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.ee9.security.AbstractLoginService;
import org.eclipse.jetty.ee9.security.IdentityService;
import org.eclipse.jetty.ee9.security.RolePrincipal;
import org.eclipse.jetty.ee9.security.UserPrincipal;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.security.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataSourceLoginService
 * <p>
 * Obtain user/password/role information from a database via jndi DataSource.
 */
public class DataSourceLoginService extends AbstractLoginService
{
    private static final Logger LOG = LoggerFactory.getLogger(DataSourceLoginService.class);

    private String _jndiName = "javax.sql.DataSource/default";
    private DataSource _datasource;
    private Server _server;
    private String _userTableName = "users";
    private String _userTableKey = "id";
    private String _userTableUserField = "username";
    private String _userTablePasswordField = "pwd";
    private String _roleTableName = "roles";
    private String _roleTableKey = "id";
    private String _roleTableRoleField = "role";
    private String _userRoleTableName = "user_roles";
    private String _userRoleTableUserKey = "user_id";
    private String _userRoleTableRoleKey = "role_id";
    private String _userSql;
    private String _roleSql;
    private boolean _createTables = false;

    /**
     * DBUser
     */
    public class DBUserPrincipal extends UserPrincipal
    {
        private int _key;

        public DBUserPrincipal(String name, Credential credential, int key)
        {
            super(name, credential);
            _key = key;
        }

        public int getKey()
        {
            return _key;
        }
    }

    public DataSourceLoginService()
    {
    }

    public DataSourceLoginService(String name)
    {
        setName(name);
    }

    public DataSourceLoginService(String name, IdentityService identityService)
    {
        setName(name);
        setIdentityService(identityService);
    }

    public void setJndiName(String jndi)
    {
        _jndiName = jndi;
    }

    public String getJndiName()
    {
        return _jndiName;
    }

    public void setServer(Server server)
    {
        _server = server;
    }

    public Server getServer()
    {
        return _server;
    }

    public void setCreateTables(boolean createTables)
    {
        _createTables = createTables;
    }

    public boolean getCreateTables()
    {
        return _createTables;
    }

    public void setUserTableName(String name)
    {
        _userTableName = name;
    }

    public String getUserTableName()
    {
        return _userTableName;
    }

    public String getUserTableKey()
    {
        return _userTableKey;
    }

    public void setUserTableKey(String tableKey)
    {
        _userTableKey = tableKey;
    }

    public String getUserTableUserField()
    {
        return _userTableUserField;
    }

    public void setUserTableUserField(String tableUserField)
    {
        _userTableUserField = tableUserField;
    }

    public String getUserTablePasswordField()
    {
        return _userTablePasswordField;
    }

    public void setUserTablePasswordField(String tablePasswordField)
    {
        _userTablePasswordField = tablePasswordField;
    }

    public String getRoleTableName()
    {
        return _roleTableName;
    }

    public void setRoleTableName(String tableName)
    {
        _roleTableName = tableName;
    }

    public String getRoleTableKey()
    {
        return _roleTableKey;
    }

    public void setRoleTableKey(String tableKey)
    {
        _roleTableKey = tableKey;
    }

    public String getRoleTableRoleField()
    {
        return _roleTableRoleField;
    }

    public void setRoleTableRoleField(String tableRoleField)
    {
        _roleTableRoleField = tableRoleField;
    }

    public String getUserRoleTableName()
    {
        return _userRoleTableName;
    }

    public void setUserRoleTableName(String roleTableName)
    {
        _userRoleTableName = roleTableName;
    }

    public String getUserRoleTableUserKey()
    {
        return _userRoleTableUserKey;
    }

    public void setUserRoleTableUserKey(String roleTableUserKey)
    {
        _userRoleTableUserKey = roleTableUserKey;
    }

    public String getUserRoleTableRoleKey()
    {
        return _userRoleTableRoleKey;
    }

    public void setUserRoleTableRoleKey(String roleTableRoleKey)
    {
        _userRoleTableRoleKey = roleTableRoleKey;
    }

    @Override
    public UserPrincipal loadUserInfo(String username)
    {
        try
        {
            try (Connection connection = getConnection();
                 PreparedStatement statement1 = connection.prepareStatement(_userSql))
            {
                statement1.setObject(1, username);
                try (ResultSet rs1 = statement1.executeQuery())
                {
                    if (rs1.next())
                    {
                        int key = rs1.getInt(_userTableKey);
                        String credentials = rs1.getString(_userTablePasswordField);

                        return new DBUserPrincipal(username, Credential.getCredential(credentials), key);
                    }
                }
            }
        }
        catch (NamingException e)
        {
            LOG.warn("No datasource for {}", _jndiName, e);
        }
        catch (SQLException e)
        {
            LOG.warn("Problem loading user info for {}", username, e);
        }
        return null;
    }

    @Override
    public List<RolePrincipal> loadRoleInfo(UserPrincipal user)
    {
        DBUserPrincipal dbuser = (DBUserPrincipal)user;

        try
        {
            try (Connection connection = getConnection();
                 PreparedStatement statement2 = connection.prepareStatement(_roleSql))
            {

                List<String> roles = new ArrayList<String>();

                statement2.setInt(1, dbuser.getKey());
                try (ResultSet rs2 = statement2.executeQuery())
                {
                    while (rs2.next())
                        roles.add(rs2.getString(_roleTableRoleField));

                    return roles.stream().map(RolePrincipal::new).collect(Collectors.toList());
                }
            }
        }
        catch (NamingException e)
        {
            LOG.warn("No datasource for {}", _jndiName, e);
        }
        catch (SQLException e)
        {
            LOG.warn("Problem loading user info for {}", user.getName(), e);
        }
        return null;
    }

    /**
     * Lookup the datasource for the jndiName and formulate the
     * necessary sql query strings based on the configured table
     * and column names.
     *
     * @throws NamingException if unable to init jndi
     * @throws SQLException if unable to init database
     */
    public void initDb() throws NamingException, SQLException
    {
        if (_datasource != null)
            return;

        @SuppressWarnings("unused")
        InitialContext ic = new InitialContext();
        assert ic != null;

        // TODO Should we try webapp scope too?

        // try finding the datasource in the Server scope
        if (_server != null)
        {
            try
            {
                _datasource = (DataSource)NamingEntryUtil.lookup(_server, _jndiName);
            }
            catch (NameNotFoundException e)
            {
                //next try the jvm scope
            }
        }

        //try finding the datasource in the jvm scope
        if (_datasource == null)
        {
            _datasource = (DataSource)NamingEntryUtil.lookup(null, _jndiName);
        }

        // set up the select statements based on the table and column names configured
        _userSql = "select " + _userTableKey + "," + _userTablePasswordField +
            " from " + _userTableName +
            " where " + _userTableUserField + " = ?";

        _roleSql = "select r." + _roleTableRoleField +
            " from " + _roleTableName + " r, " + _userRoleTableName +
            " u where u." + _userRoleTableUserKey + " = ?" +
            " and r." + _roleTableKey + " = u." + _userRoleTableRoleKey;

        prepareTables();
    }

    /**
     *
     */
    private void prepareTables()
        throws NamingException, SQLException
    {
        if (_createTables)
        {
            boolean autocommit = true;
            Connection connection = getConnection();
            try (Statement stmt = connection.createStatement())
            {
                autocommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                DatabaseMetaData metaData = connection.getMetaData();

                //check if tables exist
                String tableName = (metaData.storesLowerCaseIdentifiers() ? _userTableName.toLowerCase(Locale.ENGLISH) : (metaData.storesUpperCaseIdentifiers() ? _userTableName.toUpperCase(Locale.ENGLISH) : _userTableName));
                try (ResultSet result = metaData.getTables(null, null, tableName, null))
                {
                    if (!result.next())
                    {
                        //user table default
                        /*
                         * create table _userTableName (_userTableKey integer,
                         * _userTableUserField varchar(100) not null unique,
                         * _userTablePasswordField varchar(20) not null, primary key(_userTableKey));
                         */
                        stmt.executeUpdate("create table " + _userTableName + "(" + _userTableKey + " integer," +
                            _userTableUserField + " varchar(100) not null unique," +
                            _userTablePasswordField + " varchar(20) not null, primary key(" + _userTableKey + "))");
                        if (LOG.isDebugEnabled())
                            LOG.debug("Created table {}", _userTableName);
                    }
                }

                tableName = (metaData.storesLowerCaseIdentifiers() ? _roleTableName.toLowerCase(Locale.ENGLISH) : (metaData.storesUpperCaseIdentifiers() ? _roleTableName.toUpperCase(Locale.ENGLISH) : _roleTableName));
                try (ResultSet result = metaData.getTables(null, null, tableName, null))
                {
                    if (!result.next())
                    {
                        //role table default
                        /*
                         * create table _roleTableName (_roleTableKey integer,
                         * _roleTableRoleField varchar(100) not null unique, primary key(_roleTableKey));
                         */
                        String str = "create table " + _roleTableName + " (" + _roleTableKey + " integer, " +
                            _roleTableRoleField + " varchar(100) not null unique, primary key(" + _roleTableKey + "))";
                        stmt.executeUpdate(str);
                        if (LOG.isDebugEnabled())
                            LOG.debug("Created table {}", _roleTableName);
                    }
                }

                tableName = (metaData.storesLowerCaseIdentifiers() ? _userRoleTableName.toLowerCase(Locale.ENGLISH) : (metaData.storesUpperCaseIdentifiers() ? _userRoleTableName.toUpperCase(Locale.ENGLISH) : _userRoleTableName));
                try (ResultSet result = metaData.getTables(null, null, tableName, null))
                {
                    if (!result.next())
                    {
                        //user-role table
                        /*
                         * create table _userRoleTableName (_userRoleTableUserKey integer,
                         * _userRoleTableRoleKey integer,
                         * primary key (_userRoleTableUserKey, _userRoleTableRoleKey));
                         *
                         * create index idx_user_role on _userRoleTableName (_userRoleTableUserKey);
                         */
                        stmt.executeUpdate("create table " + _userRoleTableName + " (" + _userRoleTableUserKey + " integer, " +
                            _userRoleTableRoleKey + " integer, " +
                            "primary key (" + _userRoleTableUserKey + ", " + _userRoleTableRoleKey + "))");
                        stmt.executeUpdate("create index indx_user_role on " + _userRoleTableName + "(" + _userRoleTableUserKey + ")");
                        if (LOG.isDebugEnabled())
                            LOG.debug("Created table {} and index", _userRoleTableName);
                    }
                }
                connection.commit();
            }
            finally
            {
                try
                {
                    connection.setAutoCommit(autocommit);
                }
                catch (SQLException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Prepare tables", e);
                }
                finally
                {
                    try
                    {
                        connection.close();
                    }
                    catch (SQLException e)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Prepare tables", e);
                    }
                }
            }
        }
        else if (LOG.isDebugEnabled())
        {
            LOG.debug("createTables false");
        }
    }

    /**
     *
     */
    private Connection getConnection()
        throws NamingException, SQLException
    {
        initDb();
        return _datasource.getConnection();
    }
}

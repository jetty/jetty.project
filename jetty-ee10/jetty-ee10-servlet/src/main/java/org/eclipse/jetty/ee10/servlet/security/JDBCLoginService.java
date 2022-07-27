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

package org.eclipse.jetty.ee10.servlet.security;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Credential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC as a source of user authentication and authorization information.
 * Uses one database connection that is lazily initialized. Reconnect on failures.
 */
public class JDBCLoginService extends AbstractLoginService
{
    private static final Logger LOG = LoggerFactory.getLogger(JDBCLoginService.class);

    protected String _config;
    protected String _jdbcDriver;
    protected String _url;
    protected String _userName;
    protected String _password;
    protected String _userTableKey;
    protected String _userTablePasswordField;
    protected String _roleTableRoleField;
    protected String _userSql;
    protected String _roleSql;
    protected Connection _con;

    /**
     * JDBCUserPrincipal
     * 
     * A UserPrincipal with extra jdbc key info.
     */
    public class JDBCUserPrincipal extends UserPrincipal
    {
        final int _userKey;

        public JDBCUserPrincipal(String name, Credential credential, int key)
        {
            super(name, credential);
            _userKey = key;
        }

        public int getUserKey()
        {
            return _userKey;
        }
    }

    public JDBCLoginService()
    {
    }

    public JDBCLoginService(String name)
    {
        setName(name);
    }

    public JDBCLoginService(String name, String config)
    {
        setName(name);
        setConfig(config);
    }

    public JDBCLoginService(String name, IdentityService identityService, String config)
    {
        setName(name);
        setIdentityService(identityService);
        setConfig(config);
    }

    @Override
    protected void doStart() throws Exception
    {
        Properties properties = new Properties();
        Resource resource = Resource.newResource(_config);
        try (InputStream in = resource.newInputStream())
        {
            properties.load(in);
        }
        _jdbcDriver = properties.getProperty("jdbcdriver");
        _url = properties.getProperty("url");
        _userName = properties.getProperty("username");
        _password = properties.getProperty("password");
        _userTableKey = properties.getProperty("usertablekey");
        _userTablePasswordField = properties.getProperty("usertablepasswordfield");
        _roleTableRoleField = properties.getProperty("roletablerolefield");

        final String userTable = properties.getProperty("usertable");
        final String userTableUserField = properties.getProperty("usertableuserfield");
        final String roleTable = properties.getProperty("roletable");
        final String roleTableKey = properties.getProperty("roletablekey");
        final String userRoleTable = properties.getProperty("userroletable");
        final String userRoleTableUserKey = properties.getProperty("userroletableuserkey");
        final String userRoleTableRoleKey = properties.getProperty("userroletablerolekey");

        if (_jdbcDriver == null || _jdbcDriver.equals("") ||
            _url == null || _url.equals("") ||
            _userName == null || _userName.equals("") ||
            _password == null)
        {
            LOG.warn("UserRealm {} has not been properly configured", getName());
        }

        _userSql = "select " + _userTableKey + "," + _userTablePasswordField + " from " + userTable + " where " + userTableUserField + " = ?";
        _roleSql = "select r." + _roleTableRoleField +
            " from " + roleTable + " r, " + userRoleTable +
            " u where u." + userRoleTableUserKey + " = ?" +
            " and r." + roleTableKey + " = u." + userRoleTableRoleKey;

        Loader.loadClass(_jdbcDriver).getDeclaredConstructor().newInstance();
        super.doStart();
    }

    public String getConfig()
    {
        return _config;
    }

    /**
     * Load JDBC connection configuration from properties file.
     *
     * @param config Filename or url of user properties file.
     */
    public void setConfig(String config)
    {
        if (isRunning())
            throw new IllegalStateException("Running");
        _config = config;
    }

    /**
     * Connect to database with parameters setup by loadConfig()
     */
    public Connection connectDatabase()
        throws SQLException
    {
        return DriverManager.getConnection(_url, _userName, _password);
    }

    @Override
    public UserPrincipal loadUserInfo(String username)
    {
        try
        {
            if (null == _con)
                _con = connectDatabase();

            try (PreparedStatement stat1 = _con.prepareStatement(_userSql))
            {
                stat1.setObject(1, username);
                try (ResultSet rs1 = stat1.executeQuery())
                {
                    if (rs1.next())
                    {
                        int key = rs1.getInt(_userTableKey);
                        String credentials = rs1.getString(_userTablePasswordField);

                        return new JDBCUserPrincipal(username, Credential.getCredential(credentials), key);
                    }
                }
            }
        }
        catch (SQLException e)
        {
            LOG.warn("LoginService {} could not load user {}", getName(), username, e);
            closeConnection();
        }

        return null;
    }

    @Override
    public List<RolePrincipal> loadRoleInfo(UserPrincipal user)
    {
        if (user == null)
            return null;

        JDBCUserPrincipal jdbcUser = (JDBCUserPrincipal)user;

        try
        {
            if (null == _con)
                _con = connectDatabase();

            List<String> roles = new ArrayList<String>();

            try (PreparedStatement stat2 = _con.prepareStatement(_roleSql))
            {
                stat2.setInt(1, jdbcUser.getUserKey());
                try (ResultSet rs2 = stat2.executeQuery())
                {
                    while (rs2.next())
                        roles.add(rs2.getString(_roleTableRoleField));
                                        
                    return roles.stream().map(RolePrincipal::new).collect(Collectors.toList());
                }
            }
        }
        catch (SQLException e)
        {
            LOG.warn("LoginService {} could not load roles for user {}", getName(), user.getName(), e);
            closeConnection();
        }

        return null;
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnection();
        super.doStop();
    }

    /**
     * Close an existing connection
     */
    private void closeConnection()
    {
        if (_con != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Closing db connection for JDBCLoginService");
            try
            {
                _con.close();
            }
            catch (Exception e)
            {
                LOG.trace("IGNORED", e);
            }
        }
        _con = null;
    }
}

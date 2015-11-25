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

package org.eclipse.jetty.security;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Credential;

/* ------------------------------------------------------------ */
/**
 * HashMapped User Realm with JDBC as data source. 
 * The login() method checks the inherited Map for the user. If the user is not
 * found, it will fetch details from the database and populate the inherited
 * Map. It then calls the superclass login() method to perform the actual
 * authentication. Periodically (controlled by configuration parameter),
 * internal hashes are cleared. Caching can be disabled by setting cache refresh
 * interval to zero. Uses one database connection that is initialized at
 * startup. Reconnect on failures. authenticate() is 'synchronized'.
 * 
 * An example properties file for configuration is in
 * $JETTY_HOME/etc/jdbcRealm.properties
 * 
 * @version $Id: JDBCLoginService.java 4792 2009-03-18 21:55:52Z gregw $
 * 
 * 
 * 
 * 
 */

public class JDBCLoginService extends MappedLoginService
{
    private static final Logger LOG = Log.getLogger(JDBCLoginService.class);

    protected String _config;
    protected String _jdbcDriver;
    protected String _url;
    protected String _userName;
    protected String _password;
    protected String _userTableKey;
    protected String _userTablePasswordField;
    protected String _roleTableRoleField;
    protected int _cacheTime;
    protected long _lastHashPurge;
    protected Connection _con;
    protected String _userSql;
    protected String _roleSql;

    
    /**
     * JDBCKnownUser
     *
     *
     */
    public class JDBCKnownUser extends KnownUser
    {
        int _userKey;
        
        /**
         * @param name
         * @param credential
         */
        public JDBCKnownUser(String name, Credential credential, int key)
        {
            super(name, credential);
            _userKey = key;
        }
        
        
        public int getUserKey ()
        {
            return _userKey;
        }
    }

    /* ------------------------------------------------------------ */
    public JDBCLoginService()
        throws IOException
    {
    }
    
    /* ------------------------------------------------------------ */
    public JDBCLoginService(String name)
        throws IOException
    {
        setName(name);
    }
    
    /* ------------------------------------------------------------ */
    public JDBCLoginService(String name, String config)
        throws IOException
    {
        setName(name);
        setConfig(config);
    }
    
    /* ------------------------------------------------------------ */
    public JDBCLoginService(String name, IdentityService identityService, String config)
        throws IOException
    {
        setName(name);
        setIdentityService(identityService);
        setConfig(config);
    }


    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.security.MappedLoginService#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        Properties properties = new Properties();
        Resource resource = Resource.newResource(_config);
        try (InputStream in = resource.getInputStream())
        {
            properties.load(in);
        }
        _jdbcDriver = properties.getProperty("jdbcdriver");
        _url = properties.getProperty("url");
        _userName = properties.getProperty("username");
        _password = properties.getProperty("password");
        String _userTable = properties.getProperty("usertable");
        _userTableKey = properties.getProperty("usertablekey");
        String _userTableUserField = properties.getProperty("usertableuserfield");
        _userTablePasswordField = properties.getProperty("usertablepasswordfield");
        String _roleTable = properties.getProperty("roletable");
        String _roleTableKey = properties.getProperty("roletablekey");
        _roleTableRoleField = properties.getProperty("roletablerolefield");
        String _userRoleTable = properties.getProperty("userroletable");
        String _userRoleTableUserKey = properties.getProperty("userroletableuserkey");
        String _userRoleTableRoleKey = properties.getProperty("userroletablerolekey");
        _cacheTime = new Integer(properties.getProperty("cachetime"));

        if (_jdbcDriver == null || _jdbcDriver.equals("")
            || _url == null
            || _url.equals("")
            || _userName == null
            || _userName.equals("")
            || _password == null
            || _cacheTime < 0)
        {
            LOG.warn("UserRealm " + getName() + " has not been properly configured");
        }
        _cacheTime *= 1000;
        _lastHashPurge = 0;
        _userSql = "select " + _userTableKey + "," + _userTablePasswordField + " from " + _userTable + " where " + _userTableUserField + " = ?";
        _roleSql = "select r." + _roleTableRoleField
                   + " from "
                   + _roleTable
                   + " r, "
                   + _userRoleTable
                   + " u where u."
                   + _userRoleTableUserKey
                   + " = ?"
                   + " and r."
                   + _roleTableKey
                   + " = u."
                   + _userRoleTableRoleKey;
        
        Loader.loadClass(_jdbcDriver).newInstance();
        super.doStart();
    }


    /* ------------------------------------------------------------ */
    public String getConfig()
    {
        return _config;
    }

    /* ------------------------------------------------------------ */
    /**
     * Load JDBC connection configuration from properties file.
     * 
     * @param config Filename or url of user properties file.
     */
    public void setConfig(String config)
    {        
        if (isRunning())
            throw new IllegalStateException("Running");
        _config=config;
    }

    /* ------------------------------------------------------------ */
    /**
     * (re)Connect to database with parameters setup by loadConfig()
     */
    public void connectDatabase()
    {
        try
        {
            Class.forName(_jdbcDriver);
            _con = DriverManager.getConnection(_url, _userName, _password);
        }
        catch (SQLException e)
        {
            LOG.warn("UserRealm " + getName() + " could not connect to database; will try later", e);
        }
        catch (ClassNotFoundException e)
        {
            LOG.warn("UserRealm " + getName() + " could not connect to database; will try later", e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        long now = System.currentTimeMillis();
        if (now - _lastHashPurge > _cacheTime || _cacheTime == 0)
        {
            _users.clear();
            _lastHashPurge = now;
            closeConnection();
        }
        
        return super.login(username,credentials, request);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void loadUsers()
    {   
    }
    
    /* ------------------------------------------------------------ */
    @Deprecated
    protected UserIdentity loadUser(String username)
    {
        try
        {
            if (null == _con) 
                connectDatabase();

            if (null == _con) 
                throw new SQLException("Can't connect to database");

            try (PreparedStatement stat1 = _con.prepareStatement(_userSql))
            {
                stat1.setObject(1, username);
                try (ResultSet rs1 = stat1.executeQuery())
                {
                    if (rs1.next())
                    {
                        int key = rs1.getInt(_userTableKey);
                        String credentials = rs1.getString(_userTablePasswordField);


                        List<String> roles = new ArrayList<String>();

                        try (PreparedStatement stat2 = _con.prepareStatement(_roleSql))
                        {
                            stat2.setInt(1, key);
                            try (ResultSet rs2 = stat2.executeQuery())
                            {
                                while (rs2.next())
                                    roles.add(rs2.getString(_roleTableRoleField));
                            }
                        }
                        return putUser(username, Credential.getCredential(credentials), roles.toArray(new String[roles.size()]));
                    }
                }
            }
        }
        catch (SQLException e)
        {
            LOG.warn("UserRealm " + getName() + " could not load user information from database", e);
            closeConnection();
        }
        return null;
    }


    /** 
     * @see org.eclipse.jetty.security.MappedLoginService#loadUserInfo(java.lang.String)
     * @Override
     */
    public KnownUser loadUserInfo (String username)
    {
        try
        {
            if (null == _con) 
                connectDatabase();

            if (null == _con) 
                throw new SQLException("Can't connect to database");

            try (PreparedStatement stat1 = _con.prepareStatement(_userSql))
            {
                stat1.setObject(1, username);
                try (ResultSet rs1 = stat1.executeQuery())
                {
                    if (rs1.next())
                    {
                        int key = rs1.getInt(_userTableKey);
                        String credentials = rs1.getString(_userTablePasswordField);

                        return new JDBCKnownUser (username, Credential.getCredential(credentials), key);
                    }
                }
            }
        }
        catch (SQLException e)
        {
            LOG.warn("UserRealm " + getName() + " could not load user information from database", e);
            closeConnection();
        }
        
        return null;
    }

    
    
    /** 
     * @see org.eclipse.jetty.security.MappedLoginService#loadRoleInfo(org.eclipse.jetty.security.MappedLoginService.KnownUser)
     * @Override
     */
    public String[] loadRoleInfo (KnownUser user)
    {
        JDBCKnownUser jdbcUser = (JDBCKnownUser)user;
        
        try
        {
            if (null == _con) 
                connectDatabase();

            if (null == _con) 
                throw new SQLException("Can't connect to database");
            
            
            List<String> roles = new ArrayList<String>();

            try (PreparedStatement stat2 = _con.prepareStatement(_roleSql))
            {
                stat2.setInt(1, jdbcUser.getUserKey());
                try (ResultSet rs2 = stat2.executeQuery())
                {
                    while (rs2.next())
                        roles.add(rs2.getString(_roleTableRoleField));
                    return roles.toArray(new String[roles.size()]);
                }
            }
        }
        catch (SQLException e)
        {
            LOG.warn("UserRealm " + getName() + " could not load user information from database", e);
            closeConnection();
        }
        
        return null;
    }
    
   
    

    /**
     * Close an existing connection
     */
    private void closeConnection ()
    {
        if (_con != null)
        {
            if (LOG.isDebugEnabled()) LOG.debug("Closing db connection for JDBCUserRealm");
            try { _con.close(); }catch (Exception e) {LOG.ignore(e);}
        }
        _con = null;
    }

}

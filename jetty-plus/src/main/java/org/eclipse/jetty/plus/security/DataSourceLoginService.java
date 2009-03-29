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


package org.eclipse.jetty.plus.security;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.eclipse.jetty.http.security.Password;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.MappedLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.log.Log;


/**
 *
 * //TODO JASPI cf JDBCLoginService
 * DataSourceUserRealm
 *
 * Obtain user/password/role information from a database
 * via jndi DataSource.
 */
public class DataSourceLoginService extends MappedLoginService
{
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
    private int _cacheMs = 30000;
    private long _lastHashPurge = 0;
    private String _userSql;
    private String _roleSql;
    private boolean _createTables = false;

    /* ------------------------------------------------------------ */
    public DataSourceLoginService()
    {
    }
    
    /* ------------------------------------------------------------ */
    public DataSourceLoginService(String name)
    {
        setName(name);
    }
    
    /* ------------------------------------------------------------ */
    public DataSourceLoginService(String name, IdentityService identityService)
    {
        setName(name);
        setIdentityService(identityService);
    }

    /* ------------------------------------------------------------ */
    public void setJndiName (String jndi)
    {
        _jndiName = jndi;
    }

    /* ------------------------------------------------------------ */
    public String getJndiName ()
    {
        return _jndiName;
    }

    /* ------------------------------------------------------------ */
    public void setServer (Server server)
    {
        _server=server;
    }

    /* ------------------------------------------------------------ */
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    public void setCreateTables(boolean createTables)
    {
        _createTables = createTables;
    }

    /* ------------------------------------------------------------ */
    public boolean getCreateTables()
    {
        return _createTables;
    }

    /* ------------------------------------------------------------ */
    public void setUserTableName (String name)
    {
        _userTableName=name;
    }

    /* ------------------------------------------------------------ */
    public String getUserTableName()
    {
        return _userTableName;
    }

    /* ------------------------------------------------------------ */
    public String getUserTableKey()
    {
        return _userTableKey;
    }


    /* ------------------------------------------------------------ */
    public void setUserTableKey(String tableKey)
    {
        _userTableKey = tableKey;
    }


    /* ------------------------------------------------------------ */
    public String getUserTableUserField()
    {
        return _userTableUserField;
    }


    /* ------------------------------------------------------------ */
    public void setUserTableUserField(String tableUserField)
    {
        _userTableUserField = tableUserField;
    }


    /* ------------------------------------------------------------ */
    public String getUserTablePasswordField()
    {
        return _userTablePasswordField;
    }


    /* ------------------------------------------------------------ */
    public void setUserTablePasswordField(String tablePasswordField)
    {
        _userTablePasswordField = tablePasswordField;
    }


    /* ------------------------------------------------------------ */
    public String getRoleTableName()
    {
        return _roleTableName;
    }


    /* ------------------------------------------------------------ */
    public void setRoleTableName(String tableName)
    {
        _roleTableName = tableName;
    }


    /* ------------------------------------------------------------ */
    public String getRoleTableKey()
    {
        return _roleTableKey;
    }


    /* ------------------------------------------------------------ */
    public void setRoleTableKey(String tableKey)
    {
        _roleTableKey = tableKey;
    }


    /* ------------------------------------------------------------ */
    public String getRoleTableRoleField()
    {
        return _roleTableRoleField;
    }


    /* ------------------------------------------------------------ */
    public void setRoleTableRoleField(String tableRoleField)
    {
        _roleTableRoleField = tableRoleField;
    }


    /* ------------------------------------------------------------ */
    public String getUserRoleTableName()
    {
        return _userRoleTableName;
    }


    /* ------------------------------------------------------------ */
    public void setUserRoleTableName(String roleTableName)
    {
        _userRoleTableName = roleTableName;
    }


    /* ------------------------------------------------------------ */
    public String getUserRoleTableUserKey()
    {
        return _userRoleTableUserKey;
    }


    /* ------------------------------------------------------------ */
    public void setUserRoleTableUserKey(String roleTableUserKey)
    {
        _userRoleTableUserKey = roleTableUserKey;
    }


    /* ------------------------------------------------------------ */
    public String getUserRoleTableRoleKey()
    {
        return _userRoleTableRoleKey;
    }


    /* ------------------------------------------------------------ */
    public void setUserRoleTableRoleKey(String roleTableRoleKey)
    {
        _userRoleTableRoleKey = roleTableRoleKey;
    }

    /* ------------------------------------------------------------ */
    public void setCacheMs (int ms)
    {
        _cacheMs=ms;
    }

    /* ------------------------------------------------------------ */
    public int getCacheMs ()
    {
        return _cacheMs;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void loadUsers()
    {
    }

    /* ------------------------------------------------------------ */
    /** Load user's info from database.
     * 
     * @param user
     */
    @Override
    protected UserIdentity loadUser (String userName)
    {
        Connection connection = null;
        try
        {        
            initDb();
            connection = getConnection();
            
            PreparedStatement statement = connection.prepareStatement(_userSql);
            statement.setObject(1, userName);
            ResultSet rs = statement.executeQuery();
    
            if (rs.next())
            {
                int key = rs.getInt(_userTableKey);
                String credentials = rs.getString(_userTablePasswordField); 
                statement.close();
                
                statement = connection.prepareStatement(_roleSql);
                statement.setInt(1, key);
                rs = statement.executeQuery();
                List<String> roles = new ArrayList<String>();
                while (rs.next())
                    roles.add(rs.getString(_roleTableRoleField));    
                statement.close(); 
                return putUser(userName,new Password(credentials), roles.toArray(new String[roles.size()]));
            }
        }
        catch (NamingException e)
        {
            Log.warn("No datasource for "+_jndiName, e);
        }
        catch (SQLException e)
        {
            Log.warn("Problem loading user info for "+userName, e);
        }
        finally
        {
            if (connection != null)
            {
                try
                {
                    connection.close();
                }
                catch (SQLException x)
                {
                    Log.warn("Problem closing connection", x);
                }
                finally
                {
                    connection = null;
                }
            }
        }
        return null;
    }
   
    /* ------------------------------------------------------------ */
    /**
     * Lookup the datasource for the jndiName and formulate the
     * necessary sql query strings based on the configured table
     * and column names.
     * 
     * @throws NamingException
     */
    public void initDb() throws NamingException, SQLException
    {
        if (_datasource != null)
            return;
        
        InitialContext ic = new InitialContext();
        
        //TODO webapp scope?
        
        //try finding the datasource in the Server scope
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
        if (_datasource==null)
        {
            _datasource = (DataSource)NamingEntryUtil.lookup(null, _jndiName);
        }

        // set up the select statements based on the table and column names configured
        _userSql = "select " + _userTableKey + "," + _userTablePasswordField 
                  + " from " + _userTableName 
                  + " where "+ _userTableUserField + " = ?";
        
        _roleSql = "select r." + _roleTableRoleField
                  + " from " + _roleTableName + " r, " + _userRoleTableName 
                  + " u where u."+ _userRoleTableUserKey + " = ?"
                  + " and r." + _roleTableKey + " = u." + _userRoleTableRoleKey;
        
        prepareTables();
    }
    
    
    
    private void prepareTables()
    throws NamingException, SQLException
    {
        Connection connection = null;
        boolean autocommit = true; 
        
        if (_createTables)
        {
            try
            {
                connection = getConnection();
                autocommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                DatabaseMetaData metaData = connection.getMetaData();
                
                //check if tables exist
                String tableName = (metaData.storesLowerCaseIdentifiers()? _userTableName.toLowerCase(): (metaData.storesUpperCaseIdentifiers()?_userTableName.toUpperCase(): _userTableName));
                ResultSet result = metaData.getTables(null, null, tableName, null);
                if (!result.next())
                {                
                    //user table default
                    /*
                     * create table _userTableName (_userTableKey integer,
                     * _userTableUserField varchar(100) not null unique,
                     * _userTablePasswordField varchar(20) not null, primary key(_userTableKey));
                     */
                    connection.createStatement().executeUpdate("create table "+_userTableName+ "("+_userTableKey+" integer,"+
                            _userTableUserField+" varchar(100) not null unique,"+
                            _userTablePasswordField+" varchar(20) not null, primary key("+_userTableKey+"))");
                    if (Log.isDebugEnabled()) Log.debug("Created table "+_userTableName);
                }
                
                result.close();

                tableName = (metaData.storesLowerCaseIdentifiers()? _roleTableName.toLowerCase(): (metaData.storesUpperCaseIdentifiers()?_roleTableName.toUpperCase(): _roleTableName));
                result = metaData.getTables(null, null, tableName, null);
                if (!result.next())
                {
                    //role table default
                    /*
                     * create table _roleTableName (_roleTableKey integer,
                     * _roleTableRoleField varchar(100) not null unique, primary key(_roleTableKey));
                     */
                    String str = "create table "+_roleTableName+" ("+_roleTableKey+" integer, "+
                    _roleTableRoleField+" varchar(100) not null unique, primary key("+_roleTableKey+"))";
                    connection.createStatement().executeUpdate(str);
                    if (Log.isDebugEnabled()) Log.debug("Created table "+_roleTableName);
                }
                
                result.close();

                tableName = (metaData.storesLowerCaseIdentifiers()? _userRoleTableName.toLowerCase(): (metaData.storesUpperCaseIdentifiers()?_userRoleTableName.toUpperCase(): _userRoleTableName));
                result = metaData.getTables(null, null, tableName, null);
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
                    connection.createStatement().executeUpdate("create table "+_userRoleTableName+" ("+_userRoleTableUserKey+" integer, "+
                            _userRoleTableRoleKey+" integer, "+
                            "primary key ("+_userRoleTableUserKey+", "+_userRoleTableRoleKey+"))");                   
                    connection.createStatement().executeUpdate("create index indx_user_role on "+_userRoleTableName+"("+_userRoleTableUserKey+")");
                    if (Log.isDebugEnabled()) Log.debug("Created table "+_userRoleTableName +" and index");
                }
                
                result.close();   
                connection.commit();
            }
            finally
            {
                if (connection != null)
                {
                    try
                    {
                        connection.setAutoCommit(autocommit);
                        connection.close();
                    }
                    catch (SQLException e)
                    {
                        if (Log.isDebugEnabled()) Log.debug("Prepare tables", e);
                    }
                    finally
                    {
                        connection = null;
                    }
                }
            }
        }
        else if (Log.isDebugEnabled())
        {
            Log.debug("createTables false");
        }
    }
    
    
    private Connection getConnection () 
    throws NamingException, SQLException
    {
        initDb();
        return _datasource.getConnection();
    }

}

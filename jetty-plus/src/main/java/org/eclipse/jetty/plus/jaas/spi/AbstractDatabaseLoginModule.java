// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.jaas.spi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.http.security.Credential;
import org.eclipse.jetty.util.log.Log;

/**
 * AbstractDatabaseLoginModule
 *
 * Abstract base class for LoginModules that interact with a 
 * database to retrieve authentication and authorization information.
 * Used by the JDBCLoginModule and DataSourceLoginModule.
 *
 */
public abstract class AbstractDatabaseLoginModule extends AbstractLoginModule
{
    private String userQuery;
    private String rolesQuery;
    private String dbUserTable;
    private String dbUserTableUserField;
    private String dbUserTableCredentialField;
    private String dbUserRoleTable;
    private String dbUserRoleTableUserField;
    private String dbUserRoleTableRoleField;
    
    
    
    
    /**
     * @return a java.sql.Connection from the database
     * @throws Exception
     */
    public abstract Connection getConnection () throws Exception;
    
   
    
    /* ------------------------------------------------ */
    /** Load info from database
     * @param userName user info to load
     * @exception SQLException 
     */
    public UserInfo getUserInfo (String userName)
        throws Exception
    {
        Connection connection = null;
        
        try
        {
            connection = getConnection();
            
            //query for credential
            PreparedStatement statement = connection.prepareStatement (userQuery);
            statement.setString (1, userName);
            ResultSet results = statement.executeQuery();
            String dbCredential = null;
            if (results.next())
            {
                dbCredential = results.getString(1);
            }
            results.close();
            statement.close();
            
            //query for role names
            statement = connection.prepareStatement (rolesQuery);
            statement.setString (1, userName);
            results = statement.executeQuery();
            List roles = new ArrayList();
            
            while (results.next())
            {
                String roleName = results.getString (1);
                roles.add (roleName);
            }
            
            results.close();
            statement.close();
            
            return dbCredential==null ? null : new UserInfo (userName, 
                    Credential.getCredential(dbCredential), roles);
        }
        finally
        {
            if (connection != null) connection.close();
        }
    }
    

    public void initialize(Subject subject,
            CallbackHandler callbackHandler,
            Map sharedState,
            Map options)
    {
        super.initialize(subject, callbackHandler, sharedState, options);
        
        //get the user credential query out of the options
        dbUserTable = (String)options.get("userTable");
        dbUserTableUserField = (String)options.get("userField");
        dbUserTableCredentialField = (String)options.get("credentialField");
        
        userQuery = "select "+dbUserTableCredentialField+" from "+dbUserTable+" where "+dbUserTableUserField+"=?";
        
        
        //get the user roles query out of the options
        dbUserRoleTable = (String)options.get("userRoleTable");
        dbUserRoleTableUserField = (String)options.get("userRoleUserField");
        dbUserRoleTableRoleField = (String)options.get("userRoleRoleField");
        
        rolesQuery = "select "+dbUserRoleTableRoleField+" from "+dbUserRoleTable+" where "+dbUserRoleTableUserField+"=?";
        
        if(Log.isDebugEnabled())Log.debug("userQuery = "+userQuery);
        if(Log.isDebugEnabled())Log.debug("rolesQuery = "+rolesQuery);
    }
}

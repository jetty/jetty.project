//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.jaas.spi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Credential;

/**
 * AbstractDatabaseLoginModule
 *
 * Abstract base class for LoginModules that interact with a
 * database to retrieve authentication and authorization information.
 * Used by the JDBCLoginModule and DataSourceLoginModule.
 */
public abstract class AbstractDatabaseLoginModule extends AbstractLoginModule
{
    private static final Logger LOG = Log.getLogger(AbstractDatabaseLoginModule.class);

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
     * @throws Exception if unable to get the connection
     */
    public abstract Connection getConnection() throws Exception;

    public class JDBCUserInfo extends UserInfo
    {
        public JDBCUserInfo(String userName, Credential credential)
        {
            super(userName, credential);
        }

        @Override
        public List<String> doFetchRoles()
            throws Exception
        {
            return getRoles(getUserName());
        }
    }

    /**
     * Load info from database
     *
     * @param userName user info to load
     * @throws Exception if unable to get the user info
     */
    @Override
    public UserInfo getUserInfo(String userName)
        throws Exception
    {
        try (Connection connection = getConnection())
        {

            //query for credential
            String dbCredential = null;
            try (PreparedStatement statement = connection.prepareStatement(userQuery))
            {
                statement.setString(1, userName);
                try (ResultSet results = statement.executeQuery())
                {
                    if (results.next())
                    {
                        dbCredential = results.getString(1);
                    }
                }
            }

            if (dbCredential == null)
            {
                return null;
            }

            return new JDBCUserInfo(userName, Credential.getCredential(dbCredential));
        }
    }

    public List<String> getRoles(String userName)
        throws Exception
    {
        List<String> roles = new ArrayList<String>();

        try (Connection connection = getConnection())
        {
            //query for role names

            try (PreparedStatement statement = connection.prepareStatement(rolesQuery))
            {
                statement.setString(1, userName);
                try (ResultSet results = statement.executeQuery())
                {
                    while (results.next())
                    {
                        String roleName = results.getString(1);
                        roles.add(roleName);
                    }
                }
            }
        }

        return roles;
    }

    @Override
    public void initialize(Subject subject,
                           CallbackHandler callbackHandler,
                           Map<String, ?> sharedState,
                           Map<String, ?> options)
    {
        super.initialize(subject, callbackHandler, sharedState, options);

        //get the user credential query out of the options
        dbUserTable = (String)options.get("userTable");
        dbUserTableUserField = (String)options.get("userField");
        dbUserTableCredentialField = (String)options.get("credentialField");

        userQuery = "select " + dbUserTableCredentialField + " from " + dbUserTable + " where " + dbUserTableUserField + "=?";

        //get the user roles query out of the options
        dbUserRoleTable = (String)options.get("userRoleTable");
        dbUserRoleTableUserField = (String)options.get("userRoleUserField");
        dbUserRoleTableRoleField = (String)options.get("userRoleRoleField");

        rolesQuery = "select " + dbUserRoleTableRoleField + " from " + dbUserRoleTable + " where " + dbUserRoleTableUserField + "=?";

        if (LOG.isDebugEnabled())
            LOG.debug("userQuery = " + userQuery);
        if (LOG.isDebugEnabled())
            LOG.debug("rolesQuery = " + rolesQuery);
    }
}

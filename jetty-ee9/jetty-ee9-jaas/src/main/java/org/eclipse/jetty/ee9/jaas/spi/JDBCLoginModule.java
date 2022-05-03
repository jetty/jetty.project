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

package org.eclipse.jetty.ee9.jaas.spi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.util.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>JAAS LoginModule to retrieve user information from
 * a database and authenticate the user.</p>
 * <p>Notes</p>
 * <p>This version uses plain old JDBC connections NOT DataSources.</p>
 */
public class JDBCLoginModule extends AbstractDatabaseLoginModule
{
    private static final Logger LOG = LoggerFactory.getLogger(JDBCLoginModule.class);

    private String dbDriver;
    private String dbUrl;
    private String dbUserName;
    private String dbPassword;

    /**
     * Get a connection from the DriverManager
     *
     * @return the connection for this datasource
     * @throws Exception if unable to get the connection
     */
    @Override
    public Connection getConnection()
        throws Exception
    {
        if (!((dbDriver != null) && (dbUrl != null)))
            throw new IllegalStateException("Database connection information not configured");

        if (LOG.isDebugEnabled())
            LOG.debug("Connecting using dbDriver={} dbUserName={}, dbPassword={}", dbDriver, dbUserName, dbUrl);

        return DriverManager.getConnection(dbUrl,
            dbUserName,
            dbPassword);
    }

    /**
     * Init LoginModule.
     * <p>
     * Called once by JAAS after new instance created.
     *
     * @param subject the subject
     * @param callbackHandler the callback handler
     * @param sharedState the shared state map
     * @param options the options map
     */
    @Override
    public void initialize(Subject subject,
                           CallbackHandler callbackHandler,
                           Map<String, ?> sharedState,
                           Map<String, ?> options)
    {
        try
        {
            super.initialize(subject, callbackHandler, sharedState, options);

            //get the jdbc  username/password, jdbc url out of the options
            dbDriver = (String)options.get("dbDriver");
            dbUrl = (String)options.get("dbUrl");
            dbUserName = (String)options.get("dbUserName");
            dbPassword = (String)options.get("dbPassword");

            if (dbUserName == null)
                dbUserName = "";

            if (dbPassword == null)
                dbPassword = "";

            if (dbDriver != null)
                Loader.loadClass(dbDriver).getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e.toString());
        }
    }
}

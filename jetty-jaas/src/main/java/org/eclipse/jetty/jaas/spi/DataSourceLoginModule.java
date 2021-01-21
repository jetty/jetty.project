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
import java.util.Map;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.sql.DataSource;

/**
 * DataSourceLoginModule
 *
 * A LoginModule that uses a DataSource to retrieve user authentication
 * and authorisation information.
 *
 * @see JDBCLoginModule
 */
public class DataSourceLoginModule extends AbstractDatabaseLoginModule
{
    private String dbJNDIName;
    private DataSource dataSource;

    /**
     * Init LoginModule.
     * <p>
     * Called once by JAAS after new instance created.
     *
     * @param subject the subject
     * @param callbackHandler the callback handler
     * @param sharedState the shared state map
     * @param options the option map
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

            //get the datasource jndi name
            dbJNDIName = (String)options.get("dbJNDIName");

            InitialContext ic = new InitialContext();
            dataSource = (DataSource)ic.lookup("java:comp/env/" + dbJNDIName);
        }
        catch (NamingException e)
        {
            throw new IllegalStateException(e.toString());
        }
    }

    /**
     * Get a connection from the DataSource
     *
     * @return the connection for the datasource
     * @throws Exception if unable to get the connection
     * @see AbstractDatabaseLoginModule#getConnection()
     */
    @Override
    public Connection getConnection()
        throws Exception
    {
        return dataSource.getConnection();
    }
}

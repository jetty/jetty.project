//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.sql.DriverManager;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/** 
 * JDBCLoginModule
 * <p>
 * JAAS LoginModule to retrieve user information from
 * a database and authenticate the user.
 * <h1>Notes</h1>
 * <p>This version uses plain old JDBC connections NOT
 * Datasources.
 */
public class JDBCLoginModule extends AbstractDatabaseLoginModule
{
    private static final Logger LOG = Log.getLogger(JDBCLoginModule.class);

    private String dbDriver;
    private String dbUrl;
    private String dbUserName;
    private String dbPassword;

    /**
     * Get a connection from the DriverManager
     * @see AbstractDatabaseLoginModule#getConnection()
     * @return the connection for this datasource
     * @throws Exception if unable to get the connection
     */
    public Connection getConnection ()
    throws Exception
    {
        if (!((dbDriver != null)
                &&
                (dbUrl != null)))
            throw new IllegalStateException ("Database connection information not configured");

        if(LOG.isDebugEnabled())LOG.debug("Connecting using dbDriver="+dbDriver+"+ dbUserName="+dbUserName+", dbPassword="+dbUrl);

        return DriverManager.getConnection (dbUrl,
                dbUserName,
                dbPassword);
    }



    /* ------------------------------------------------ */
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
    public void initialize(Subject subject,
                           CallbackHandler callbackHandler,
                           Map<String,?> sharedState,
                           Map<String,?> options)
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
                Loader.loadClass(dbDriver).newInstance();
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalStateException (e.toString());
        }
        catch (InstantiationException e)
        {
            throw new IllegalStateException (e.toString());
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException (e.toString());
        }
    }
}

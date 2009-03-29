// ========================================================================
// Copyright (c) 2002-2009 Mort Bay Consulting Pty. Ltd.
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
import java.sql.DriverManager;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;



/* ---------------------------------------------------- */
/** JDBCLoginModule
 * <p>JAAS LoginModule to retrieve user information from
 *  a database and authenticate the user.
 *
 * <p><h4>Notes</h4>
 * <p>This version uses plain old JDBC connections NOT
 * Datasources.
 *
 * <p><h4>Usage</h4>
 * <pre>
 */
/*
 * </pre>
 *
 * @see 
 * @version 1.0 Tue Apr 15 2003
 * 
 */
public class JDBCLoginModule extends AbstractDatabaseLoginModule
{
    private String dbDriver;
    private String dbUrl;
    private String dbUserName;
    private String dbPassword;

    

  

    
    /** 
     * Get a connection from the DriverManager
     * @see org.eclipse.jetty.server.server.plus.jaas.spi.AbstractDatabaseLoginModule#getConnection()
     * @return
     * @throws Exception
     */
    public Connection getConnection ()
    throws Exception
    {
        if (!((dbDriver != null)
                &&
                (dbUrl != null)))
            throw new IllegalStateException ("Database connection information not configured");
        
        if(Log.isDebugEnabled())Log.debug("Connecting using dbDriver="+dbDriver+"+ dbUserName="+dbUserName+", dbPassword="+dbUrl);
        
        return DriverManager.getConnection (dbUrl,
                dbUserName,
                dbPassword);
    }
   
   
    
    /* ------------------------------------------------ */
    /** Init LoginModule.
     * Called once by JAAS after new instance created.
     * @param subject 
     * @param callbackHandler 
     * @param sharedState 
     * @param options 
     */
    public void initialize(Subject subject,
                           CallbackHandler callbackHandler,
                           Map sharedState,
                           Map options)
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
                Loader.loadClass(this.getClass(), dbDriver).newInstance();
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

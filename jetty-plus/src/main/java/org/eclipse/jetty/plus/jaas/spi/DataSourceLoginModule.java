// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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
 * @see org.eclipse.jetty.server.server.plus.jaas.spi.JDBCLoginModule
 *
 */
public class DataSourceLoginModule extends AbstractDatabaseLoginModule
{

    private String dbJNDIName;
    private DataSource dataSource;
    
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
            
            //get the datasource jndi name
            dbJNDIName = (String)options.get("dbJNDIName");
            
            InitialContext ic = new InitialContext();
            dataSource = (DataSource)ic.lookup("java:comp/env/"+dbJNDIName);
        }
        catch (NamingException e)
        {
            throw new IllegalStateException (e.toString());
        }
    }


    /** 
     * Get a connection from the DataSource
     * @see org.eclipse.jetty.server.server.plus.jaas.spi.AbstractDatabaseLoginModule#getConnection()
     * @return
     * @throws Exception
     */
    public Connection getConnection ()
    throws Exception
    {
        return dataSource.getConnection();
    }


    
  

}

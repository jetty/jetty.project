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

package com.acme;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * MockDataSource
 */
public class MockDataSource implements DataSource
{

    /**
     * NOTE: JDK7+ new feature
     */
    @Override
    public Logger getParentLogger()
    {
        return null;
    }

    /**
     * @see javax.sql.DataSource#getConnection()
     */
    @Override
    public Connection getConnection() throws SQLException
    {
        return null;
    }

    /**
     * @see javax.sql.DataSource#getConnection(java.lang.String, java.lang.String)
     */
    @Override
    public Connection getConnection(String username, String password)
        throws SQLException
    {
        return null;
    }

    /**
     * @see javax.sql.DataSource#getLogWriter()
     */
    @Override
    public PrintWriter getLogWriter() throws SQLException
    {
        return null;
    }

    /**
     * @see javax.sql.DataSource#getLoginTimeout()
     */
    @Override
    public int getLoginTimeout() throws SQLException
    {
        return 0;
    }

    /**
     * @see javax.sql.DataSource#setLogWriter(java.io.PrintWriter)
     */
    @Override
    public void setLogWriter(PrintWriter out) throws SQLException
    {
    }

    /**
     * @see javax.sql.DataSource#setLoginTimeout(int)
     */
    @Override
    public void setLoginTimeout(int seconds) throws SQLException
    {
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        return null;
    }
}

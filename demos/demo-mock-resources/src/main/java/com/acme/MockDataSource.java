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

    @Override
    public Connection getConnection() throws SQLException
    {
        return null;
    }

    @Override
    public Connection getConnection(String username, String password)
        throws SQLException
    {
        return null;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException
    {
        return null;
    }

    @Override
    public int getLoginTimeout() throws SQLException
    {
        return 0;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException
    {
    }

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

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

package org.eclipse.jetty.session;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Some test method helper to get around some protected methods
 */
public class DatabaseTestHelper
{

    public static void setDatabaseAdaptor(JDBCSessionDataStore.SessionTableSchema sessionTableSchema, DatabaseAdaptor databaseAdaptor)
    {
        sessionTableSchema.setDatabaseAdaptor(databaseAdaptor);
    }

    public static Connection getConnection(DatabaseAdaptor databaseAdaptor) throws SQLException
    {
        return databaseAdaptor.getConnection();
    }

}

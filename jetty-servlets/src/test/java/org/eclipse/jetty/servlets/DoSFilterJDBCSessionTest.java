//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets;

import org.eclipse.jetty.server.session.DatabaseAdaptor;
import org.eclipse.jetty.server.session.JDBCSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;

public class DoSFilterJDBCSessionTest extends DoSFilterTest
{
    private static final String DRIVER_CLASS = "org.apache.derby.jdbc.EmbeddedDriver";
    private static final String DEFAULT_CONNECTION_URL = "jdbc:derby:memory:sessions;create=true";

    @Override
    protected SessionDataStore getSessionDataStore(WorkDir workDir) throws Exception
    {
        DatabaseAdaptor da = new DatabaseAdaptor();
        da.setDriverInfo(DRIVER_CLASS, DEFAULT_CONNECTION_URL);
        JDBCSessionDataStoreFactory factory = new JDBCSessionDataStoreFactory();
        factory.setDatabaseAdaptor(da);
        return factory.getSessionDataStore(null);
    }
}

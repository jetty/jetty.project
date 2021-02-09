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

package org.eclipse.jetty.server.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JDBCSessionDataStoreTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class JDBCSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    @BeforeEach
    public void setUp() throws Exception
    {
        JdbcTestHelper.prepareTables();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        JdbcTestHelper.shutdown(null);
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return JdbcTestHelper.newSessionDataStoreFactory();
    }

    @Override
    public void persistSession(SessionData data)
        throws Exception
    {
        JdbcTestHelper.insertSession(data);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        JdbcTestHelper.insertUnreadableSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(),
            data.getCreated(), data.getAccessed(), data.getLastAccessed(),
            data.getMaxInactiveMs(), data.getExpiry(), data.getCookieSet(),
            data.getLastSaved());
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return JdbcTestHelper.existsInSessionTable(data.getId(), false);
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return JdbcTestHelper.checkSessionPersisted(data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}

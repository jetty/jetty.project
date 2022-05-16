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

import org.eclipse.jetty.session.AbstractSessionDataStoreTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JDBCSessionDataStoreTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class JDBCSessionDataStoreTest extends AbstractSessionDataStoreTest
{
    public JDBCSessionDataStoreTest() throws Exception
    {
        super();
    }

    @BeforeEach
    public void configure() throws Exception
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
    
    @Test
    public void testCleanOrphans() throws Exception
    {
        super.testCleanOrphans();
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

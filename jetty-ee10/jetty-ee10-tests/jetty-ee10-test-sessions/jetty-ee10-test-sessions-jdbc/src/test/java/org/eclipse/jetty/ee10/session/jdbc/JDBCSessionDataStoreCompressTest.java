//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.session.jdbc;

import org.eclipse.jetty.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.session.JdbcTestHelper;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
public class JDBCSessionDataStoreCompressTest extends AbstractSessionDataStoreTest
{
    public static final boolean COMPRESS = true;

    class NonSerializable
    {
        int x = 10;
    }

    public JDBCSessionDataStoreCompressTest() throws Exception
    {
        super();
    }

    private String sessionTableName;

    @BeforeEach
    public void setupSessionTableName() throws Exception
    {
        this.sessionTableName = getClass().getSimpleName() + "_" + System.nanoTime();
        JdbcTestHelper.prepareTables(sessionTableName);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        JdbcTestHelper.shutdown(sessionTableName);
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return JdbcTestHelper.newSessionDataStoreFactory(sessionTableName, COMPRESS);
    }

    @Override
    public void persistSession(SessionData data)
        throws Exception
    {
        JdbcTestHelper.insertSession(data, sessionTableName, COMPRESS);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        JdbcTestHelper.insertUnreadableSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(),
            data.getCreated(), data.getAccessed(), data.getLastAccessed(),
            data.getMaxInactiveMs(), data.getExpiry(), data.getCookieSet(),
            data.getLastSaved(), sessionTableName);
    }
    
    @Test
    public void testCleanOrphans() throws Exception
    {
        super.testCleanOrphans();
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return JdbcTestHelper.existsInSessionTable(data.getId(), false, sessionTableName);
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return JdbcTestHelper.checkSessionPersisted(data, sessionTableName, COMPRESS);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testUnserializableSession() throws Exception
    {
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that has an unserializable attribute
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("xxx999", 100, now, now - 1, -1); //never expires
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setAttribute("bad", new NonSerializable());

        store.store("xxx999", data);

        data = store.load("xxx999");
        Assertions.assertNull(data.getAttribute("bad"));
    }
}

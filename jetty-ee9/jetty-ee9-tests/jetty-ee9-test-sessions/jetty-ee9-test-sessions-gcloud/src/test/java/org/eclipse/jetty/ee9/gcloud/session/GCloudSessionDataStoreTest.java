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

package org.eclipse.jetty.ee9.gcloud.session;

import org.eclipse.jetty.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * GCloudSessionDataStoreTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class GCloudSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    public GCloudSessionDataStoreTest() throws Exception
    {
        super();
    }

    public static GCloudSessionTestSupport __testSupport;

    @BeforeAll
    public static void configure() throws Exception
    {
        __testSupport = new GCloudSessionTestSupport();
        __testSupport.setUp();
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        __testSupport.tearDown();
    }

    @AfterEach
    public void teardown() throws Exception
    {
        __testSupport.deleteSessions();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return GCloudSessionTestSupport.newSessionDataStoreFactory(__testSupport.getDatastore());
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        __testSupport.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
            data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(),
            data.getCookieSet(), data.getLastSaved(), data.getAllAttributes());
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {

        __testSupport.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
            data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(),
            data.getCookieSet(), data.getLastSaved(), null);
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return __testSupport.checkSessionExists(data.getId());
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return __testSupport.checkSessionPersisted(data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}

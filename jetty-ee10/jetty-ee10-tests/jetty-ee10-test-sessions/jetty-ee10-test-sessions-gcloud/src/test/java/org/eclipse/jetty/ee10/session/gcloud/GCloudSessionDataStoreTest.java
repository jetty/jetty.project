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

package org.eclipse.jetty.ee10.session.gcloud;

import org.eclipse.jetty.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.test.tools.GCloudSessionTestSupport;
import org.junit.jupiter.api.AfterEach;
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

    public GCloudSessionTestSupport testSupport = new GCloudSessionTestSupport(getClass().getSimpleName());

    @AfterEach
    public void cleanup() throws Exception
    {
        testSupport.deleteSessions();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return testSupport.newSessionDataStoreFactory();
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        testSupport.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(),
                data.getCookieSet(), data.getLastSaved(), data.getAllAttributes());
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {

        testSupport.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
                data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(),
                data.getCookieSet(), data.getLastSaved(), null);
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return testSupport.checkSessionExists(data.getId());
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return testSupport.checkSessionPersisted(data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}

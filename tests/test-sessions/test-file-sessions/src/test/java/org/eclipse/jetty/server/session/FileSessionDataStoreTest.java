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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * FileSessionDataStoreTest
 */
@ExtendWith(WorkDirExtension.class)
public class FileSessionDataStoreTest extends AbstractSessionDataStoreTest
{
    public FileSessionDataStoreTest() throws Exception
    {
        super();
    }

    public WorkDir workDir;
    private FileTestHelper _helper;

    @BeforeEach
    public void before() throws Exception
    {
        _helper = new FileTestHelper(workDir.getEmptyPathDir());
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return _helper.newSessionDataStoreFactory();
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        _helper.createFile(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
            data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getCookieSet(), data.getAllAttributes());
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        _helper.createFile(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
            data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getCookieSet(), null);
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return (_helper.getFile(data.getId()) != null);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return _helper.checkSessionPersisted(data);
        }
        catch (Throwable e)
        {
            e.printStackTrace();
            throw e;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}

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

import org.eclipse.jetty.ee10.session.AbstractClusteredSessionScavengingTest;
import org.eclipse.jetty.session.JdbcTestHelper;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ClusteredSessionScavengingTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class ClusteredSessionScavengingTest extends AbstractClusteredSessionScavengingTest
{
    private String sessionTableName;

    @BeforeEach
    public void setupSessionTableName() throws Exception
    {
        this.sessionTableName = getClass().getSimpleName() + "_" + System.nanoTime();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return JdbcTestHelper.newSessionDataStoreFactory(sessionTableName, false);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        JdbcTestHelper.shutdown(sessionTableName);
    }
}

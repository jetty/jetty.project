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

package org.eclipse.jetty.nosql.mongodb;

import org.eclipse.jetty.ee10.session.AbstractClusteredSessionScavengingTest;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
public class ClusteredSessionScavengingTest extends AbstractClusteredSessionScavengingTest
{
    @BeforeAll
    public static void beforeClass() throws Exception
    {
        MongoTestHelper.createCollection();
    }

    @AfterAll
    public static void afterClass() throws Exception
    {
        MongoTestHelper.dropCollection();
        MongoTestHelper.shutdown();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return MongoTestHelper.newSessionDataStoreFactory();
    }
}

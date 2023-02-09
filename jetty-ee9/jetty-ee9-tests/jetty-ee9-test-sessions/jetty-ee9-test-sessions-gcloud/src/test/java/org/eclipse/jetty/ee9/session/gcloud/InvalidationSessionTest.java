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

package org.eclipse.jetty.ee9.session.gcloud;

import org.eclipse.jetty.ee9.session.AbstractClusteredInvalidationSessionTest;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * InvalidationSessionTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class InvalidationSessionTest extends AbstractClusteredInvalidationSessionTest
{
    public static GCloudSessionTestSupport __testSupport;

    @BeforeAll
    public static void setUp() throws Exception
    {
        __testSupport = new GCloudSessionTestSupport();
        __testSupport.setUp();
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        __testSupport.deleteSessions();
        __testSupport.tearDown();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return GCloudSessionTestSupport.newSessionDataStoreFactory(__testSupport.getDatastore());
    }
}

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

package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.session.AbstractClusteredSessionScavengingTest;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ClusteredSessionScavengingTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class ClusteredSessionScavengingTest extends AbstractClusteredSessionScavengingTest
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

    /**
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return GCloudSessionTestSupport.newSessionDataStoreFactory(__testSupport.getDatastore());
    }
}

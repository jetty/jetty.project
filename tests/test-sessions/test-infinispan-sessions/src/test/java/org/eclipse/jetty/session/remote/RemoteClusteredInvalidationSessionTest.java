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

package org.eclipse.jetty.session.remote;

import org.eclipse.jetty.session.AbstractClusteredInvalidationSessionTest;
import org.eclipse.jetty.session.LoggingUtil;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * InvalidationSessionTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class RemoteClusteredInvalidationSessionTest extends AbstractClusteredInvalidationSessionTest
{
    static
    {
        LoggingUtil.init();
    }

    public static RemoteInfinispanTestSupport __testSupport;

    @BeforeAll
    public static void setup() throws Exception
    {
        __testSupport = new RemoteInfinispanTestSupport("remote-session-test");
        __testSupport.setup();
    }

    @AfterAll
    public static void teardown() throws Exception
    {
        __testSupport.teardown();
        __testSupport.shutdown();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(__testSupport.getCache());
        return factory;
    }
}

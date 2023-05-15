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

package org.eclipse.jetty.ee10.session.infinispan.remote;

import org.eclipse.jetty.ee10.session.AbstractClusteredInvalidationSessionTest;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.test.tools.LoggingUtil;
import org.eclipse.jetty.session.test.tools.RemoteInfinispanTestSupport;
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

    public RemoteInfinispanTestSupport testSupport;

    public RemoteClusteredInvalidationSessionTest() throws Exception
    {
        testSupport = new RemoteInfinispanTestSupport(getClass().getSimpleName() + System.nanoTime());
        testSupport.setup();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(testSupport.getCache());
        return factory;
    }
}

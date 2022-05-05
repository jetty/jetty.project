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

package org.eclipse.jetty.session;

import org.eclipse.jetty.ee9.session.AbstractClusteredSessionScavengingTest;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ClusteredSerializedSessionScavengingTest
 */
//TODO
@Disabled
@ExtendWith(WorkDirExtension.class)
public class ClusteredSerializedSessionScavengingTest extends AbstractClusteredSessionScavengingTest
{
    public WorkDir workDir;
    public static InfinispanTestSupport testSupport;

    @BeforeEach
    public void setup() throws Exception
    {
        testSupport = new InfinispanTestSupport();
        testSupport.setUseFileStore(true);
        testSupport.setSerializeSessionData(true);
        testSupport.setup(workDir.getEmptyPathDir());
    }

    @AfterEach
    public void teardown() throws Exception
    {
        if (testSupport != null)
            testSupport.teardown();
    }

    @Override
    @Test
    public void testClusteredScavenge()
        throws Exception
    {
        super.testClusteredScavenge();
    }

    /**
     * @see org.eclipse.jetty.server.session.AbstractSessionTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setSerialization(true);
        factory.setCache(testSupport.getCache());
        return factory;
    }
}

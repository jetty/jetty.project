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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ClusteredSessionScavengingTest
 */
@ExtendWith(WorkDirExtension.class)
public class ClusteredSessionScavengingTest extends AbstractClusteredSessionScavengingTest
{
    public WorkDir workDir;
    public InfinispanTestSupport testSupport;

    @BeforeEach
    public void setup() throws Exception
    {
        testSupport = new InfinispanTestSupport();
        testSupport.setUseFileStore(true);
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
     * @see org.eclipse.jetty.server.session.AbstractTestBase#createSessionDataStoreFactory()
     */
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(testSupport.getCache());
        return factory;
    }
}

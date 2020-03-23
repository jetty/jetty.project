//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ClusteredSerializedSessionScavengingTest
 */
public class ClusteredSerializedSessionScavengingTest extends AbstractClusteredSessionScavengingTest
{
    public static InfinispanTestSupport __testSupport;

    @BeforeAll
    public static void setup() throws Exception
    {
        __testSupport = new InfinispanTestSupport();
        __testSupport.setUseFileStore(true);
        __testSupport.setSerializeSessionData(true);
        __testSupport.setup();
    }

    @AfterAll
    public static void teardown() throws Exception
    {
        if (__testSupport != null)
            __testSupport.teardown();
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
        factory.setCache(__testSupport.getCache());
        return factory;
    }
}

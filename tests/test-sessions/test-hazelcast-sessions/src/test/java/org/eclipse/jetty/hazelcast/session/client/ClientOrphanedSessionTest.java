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

package org.eclipse.jetty.hazelcast.session.client;

import org.eclipse.jetty.hazelcast.session.HazelcastTestHelper;
import org.eclipse.jetty.server.session.AbstractClusteredOrphanedSessionTest;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class ClientOrphanedSessionTest
    extends AbstractClusteredOrphanedSessionTest
{

    HazelcastTestHelper _testHelper;

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return _testHelper.createSessionDataStoreFactory(true);
    }

    @BeforeEach
    public void setUp()
    {
        _testHelper = new HazelcastTestHelper();
    }

    @AfterEach
    public void shutdown()
    {
        _testHelper.tearDown();
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.hazelcast.session.HazelcastSessionDataStoreFactory;
import org.eclipse.jetty.hazelcast.session.HazelcastTestHelper;
import org.eclipse.jetty.server.session.AbstractClusteredSessionScavengingTest;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.After;
import org.junit.Before;

public class ClientSessionScavengingTest
    extends AbstractClusteredSessionScavengingTest
{

    HazelcastTestHelper _testHelper;

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return _testHelper.createSessionDataStoreFactory(true);
    }

    @Before
    public void setUp()
    {
        _testHelper = new HazelcastTestHelper();
    }

    @After
    public void shutdown()
    {
        _testHelper.tearDown();
    }
}

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

package org.eclipse.jetty.hazelcast.session.remote;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * RemoteHazelcastSessionDataStoreTest
 */
public class RemoteHazelcastSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    @BeforeEach
    public void setup() throws Exception
    {
        //
    }

    @AfterEach
    public void teardown() throws Exception
    {
        //
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return RemoteHazelcastTestHelper.createSessionDataStoreFactory();
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        RemoteHazelcastTestHelper.createSession(data);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        //Not used by testLoadSessionFails() 
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return RemoteHazelcastTestHelper.checkSessionExists(data);
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        return RemoteHazelcastTestHelper.checkSessionPersisted(data);
    }

}



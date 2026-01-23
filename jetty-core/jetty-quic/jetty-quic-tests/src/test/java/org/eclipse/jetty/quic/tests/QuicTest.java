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

package org.eclipse.jetty.quic.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QuicTest extends AbstractQuicTest
{
    @Test
    public void testEstablishConnection() throws Exception
    {
        start(() -> new Session.Listener() {});

        Session session = Promise.Completable.<Session>with(p ->
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, p)
        ).get(5, TimeUnit.SECONDS);

        assertNotNull(session);
    }
}

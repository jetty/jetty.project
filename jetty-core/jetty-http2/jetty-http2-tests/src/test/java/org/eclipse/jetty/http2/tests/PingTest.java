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

package org.eclipse.jetty.http2.tests;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PingTest extends AbstractTest
{
    @Test
    public void testPing() throws Exception
    {
        start(new ServerSessionListener.Adapter());

        final byte[] payload = new byte[8];
        new Random().nextBytes(payload);
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener.Adapter()
        {
            @Override
            public void onPing(Session session, PingFrame frame)
            {
                assertTrue(frame.isReply());
                assertArrayEquals(payload, frame.getPayload());
                latch.countDown();
            }
        });

        PingFrame frame = new PingFrame(payload, false);
        session.ping(frame, Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}

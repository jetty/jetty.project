//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.util.Callback;
import org.junit.Assert;
import org.junit.Test;

public class PingTest extends AbstractTest
{
    @Test
    public void testPing() throws Exception
    {
        start(new ServerSessionListener.Adapter());

        final byte[] payload = new byte[8];
        new Random().nextBytes(payload);
        final CountDownLatch latch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onPing(Session session, PingFrame frame)
            {
                Assert.assertTrue(frame.isReply());
                Assert.assertArrayEquals(payload, frame.getPayload());
                latch.countDown();
            }
        });

        PingFrame frame = new PingFrame(payload, false);
        session.ping(frame, Callback.NOOP);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}

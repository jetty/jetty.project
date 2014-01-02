//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.junit.Assert;
import org.junit.Test;

public class SPDYClientFactoryTest extends AbstractTest
{
    @Test
    public void testStoppingClientFactorySendsGoAway() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        startClient(startServer(new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayResultInfo goAwayResultInfo)
            {
                latch.countDown();
            }
        }), null);

        // Sleep a while to avoid the factory is
        // stopped before a session can be opened
        TimeUnit.SECONDS.sleep(1);

        clientFactory.stop();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientFactory.getSessions().isEmpty());
    }

    @Test
    public void testSessionClosedIsRemovedFromClientFactory() throws Exception
    {
        Session session = startClient(startServer(null), null);

        session.goAway(new GoAwayInfo(5, TimeUnit.SECONDS));

        // Sleep a while to allow the factory to remove the session
        // since it is done asynchronously by the selector thread
        TimeUnit.SECONDS.sleep(1);

        Assert.assertTrue(clientFactory.getSessions().isEmpty());
    }
}

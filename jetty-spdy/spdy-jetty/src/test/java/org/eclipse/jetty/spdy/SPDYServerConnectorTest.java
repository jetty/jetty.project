/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.junit.Test;

public class SPDYServerConnectorTest extends AbstractTest
{
    @Test
    public void testStoppingServerConnectorSendsGoAway() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        startClient(startServer(null), new SessionFrameListener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayInfo goAwayInfo)
            {
                latch.countDown();
            }
        });

        // Sleep a while to avoid the connector is
        // stopped before a session can be opened
        TimeUnit.SECONDS.sleep(1);

        connector.stop();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(connector.getSessions().isEmpty());
    }

    @Test
    public void testSessionClosedIsRemovedFromServerConnector() throws Exception
    {
        Session session = startClient(startServer(null), null);

        session.goAway().get(5, TimeUnit.SECONDS);

        // Sleep a while to allow the connector to remove the session
        // since it is done asynchronously by the selector thread
        TimeUnit.SECONDS.sleep(1);

        Assert.assertTrue(connector.getSessions().isEmpty());
    }
}

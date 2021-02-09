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

package org.eclipse.jetty.client;

import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that synchronization performed from outside HttpClient does not cause deadlocks
 */
public class HttpClientSynchronizationTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSynchronizationOnException(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());
        int port = connector.getLocalPort();
        server.stop();

        int count = 10;
        final CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            Request request = client.newRequest("localhost", port)
                .scheme(scenario.getScheme());

            synchronized (this)
            {
                request.send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        synchronized (HttpClientSynchronizationTest.this)
                        {
                            assertThat(failure, Matchers.instanceOf(ConnectException.class));
                            latch.countDown();
                        }
                    }
                });
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSynchronizationOnComplete(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        int count = 10;
        final CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme());

            synchronized (this)
            {
                request.send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        synchronized (HttpClientSynchronizationTest.this)
                        {
                            assertFalse(result.isFailed());
                            latch.countDown();
                        }
                    }
                });
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}

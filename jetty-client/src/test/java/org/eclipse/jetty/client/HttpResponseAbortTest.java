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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpResponseAbortTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnBegin(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(response -> response.abort(new Exception()))
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeader(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader((response, field) ->
            {
                response.abort(new Exception());
                return true;
            })
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeaders(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeaders(response -> response.abort(new Exception()))
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContent(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    OutputStream output = response.getOutputStream();
                    output.write(1);
                    output.flush();
                    output.write(2);
                    output.flush();
                }
                catch (IOException ignored)
                {
                    // The client may have already closed, and we'll get an exception here, but it's expected
                }
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContent((response, content) -> response.abort(new Exception()))
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContentBeforeRequestTermination(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    OutputStream output = response.getOutputStream();
                    output.write(1);
                    output.flush();
                    output.write(2);
                    output.flush();
                }
                catch (IOException ignored)
                {
                    // The client may have already closed, and we'll get an exception here, but it's expected
                }
            }
        });

        final DeferredContentProvider contentProvider = new DeferredContentProvider(ByteBuffer.allocate(1));
        final AtomicInteger completes = new AtomicInteger();
        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .content(contentProvider)
            .onResponseContent((response, content) ->
            {
                try
                {
                    response.abort(new Exception());
                    contentProvider.close();
                    // Delay to let the request side to finish its processing.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            })
            .send(result ->
            {
                completes.incrementAndGet();
                assertTrue(result.isFailed());
                completeLatch.countDown();
            });
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

        // Wait to be sure that the complete event is only notified once.
        Thread.sleep(1000);

        assertEquals(1, completes.get());
    }
}

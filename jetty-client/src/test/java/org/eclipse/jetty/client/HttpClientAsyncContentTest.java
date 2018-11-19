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

package org.eclipse.jetty.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class HttpClientAsyncContentTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSmallAsyncContent(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(65);
                output.flush();
                output.write(66);
            }
        });

        final AtomicInteger contentCount = new AtomicInteger();
        final AtomicReference<Callback> callbackRef = new AtomicReference<>();
        final AtomicReference<CountDownLatch> contentLatch = new AtomicReference<>(new CountDownLatch(1));
        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .onResponseContentAsync(new Response.AsyncContentListener()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content, Callback callback)
                    {
                        contentCount.incrementAndGet();
                        callbackRef.set(callback);
                        contentLatch.get().countDown();
                    }
                })
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        completeLatch.countDown();
                    }
                });

        assertTrue(contentLatch.get().await(5, TimeUnit.SECONDS));
        Callback callback = callbackRef.get();

        // Wait a while to be sure that the parsing does not proceed.
        TimeUnit.MILLISECONDS.sleep(1000);

        assertEquals(1, contentCount.get());

        // Succeed the content callback to proceed with parsing.
        callbackRef.set(null);
        contentLatch.set(new CountDownLatch(1));
        callback.succeeded();

        assertTrue(contentLatch.get().await(5, TimeUnit.SECONDS));
        callback = callbackRef.get();

        // Wait a while to be sure that the parsing does not proceed.
        TimeUnit.MILLISECONDS.sleep(1000);

        assertEquals(2, contentCount.get());
        assertEquals(1, completeLatch.getCount());

        // Succeed the content callback to proceed with parsing.
        callbackRef.set(null);
        contentLatch.set(new CountDownLatch(1));
        callback.succeeded();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, contentCount.get());
    }
}

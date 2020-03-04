//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

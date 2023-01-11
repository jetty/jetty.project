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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InformationalResponseTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void test102Processing(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.sendError(HttpStatus.PROCESSING_102);
                response.sendError(HttpStatus.PROCESSING_102);
                response.setStatus(200);
                response.getOutputStream().print("OK");
            }
        });
        long idleTimeout = 10000;
        setStreamIdleTimeout(idleTimeout);

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                response.set(result.getResponse());
                completeLatch.countDown();
            }
        };
        client.newRequest(newURI(transport))
            .method("GET")
            .timeout(10, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(completeLatch.await(10, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("OK"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void test103EarlyHint(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.addHeader("Hint", "one");
                response.sendError(HttpStatus.EARLY_HINT_103);
                response.addHeader("Hint", "two");
                response.sendError(HttpStatus.EARLY_HINT_103);
                response.addHeader("Hint", "three");
                response.setStatus(200);
                response.getOutputStream().print("OK");
            }
        });
        long idleTimeout = 10000;
        setStreamIdleTimeout(idleTimeout);

        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                response.set(result.getResponse());
                complete.countDown();
            }
        };
        client.newRequest(newURI(transport))
            .method("GET")
            .timeout(5, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("OK"));
        assertThat(response.get().getHeaders().getValuesList("Hint"), contains("one", "two", "three"));
    }
}

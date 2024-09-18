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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class RequestReaderTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testChannelStateSucceeded(Transport transport) throws Exception
    {
        CountDownLatch servletDoneLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                request.startAsync();

                ServletInputStream inputStream = request.getInputStream();
                inputStream.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (inputStream.isReady() && !inputStream.isFinished())
                        {
                            int read = inputStream.read();
                            if (read < 0)
                                break;
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });

                response.sendError(567);

                new Thread(() ->
                {
                    try
                    {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                    finally
                    {
                        servletDoneLatch.countDown();
                    }
                }).start();
            }
        });
        CountDownLatch callbackCompletedLatch = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        server.stop();
        server.insertHandler(new Handler.Wrapper()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, Response response, Callback callback) throws Exception
            {
                return super.handle(request, response, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        callback.succeeded();
                        callbackCompletedLatch.countDown();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        callback.failed(x);
                        callbackFailure.set(x);
                        callbackCompletedLatch.countDown();
                    }
                });
            }
        });
        server.start();

        AtomicReference<Result> resultRef = new AtomicReference<>();
        try (AsyncRequestContent content = new AsyncRequestContent())
        {
            Request request = client.newRequest(newURI(transport))
                .method("POST")
                .timeout(5, TimeUnit.SECONDS)
                .body(content);
            request.send(resultRef::set);
            assertTrue(servletDoneLatch.await(5, TimeUnit.SECONDS));
        }

        await().atMost(5, TimeUnit.SECONDS).until(resultRef::get, not(nullValue()));
        Result result = resultRef.get();
        assertThat(result.getResponse().getStatus(), is(567));
        assertThat(callbackCompletedLatch.await(5, TimeUnit.SECONDS), is(true));
        assertThat(callbackFailure.get(), is(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testResetArrivingOnServer(Transport transport) throws Exception
    {
        assumeTrue(transport.isMultiplexed());

        CountDownLatch servletOnDataAvailableLatch = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch errorDoneLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onComplete(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onTimeout(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        serverError.set(event.getThrowable());
                        response.sendError(567);
                        asyncContext.complete();
                        errorDoneLatch.countDown();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event)
                    {
                    }
                });

                ServletInputStream inputStream = request.getInputStream();
                inputStream.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        servletOnDataAvailableLatch.countDown();
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });
            }
        });

        AtomicReference<Result> resultRef = new AtomicReference<>();
        try (AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.allocate(16)))
        {
            Request request = client.newRequest(newURI(transport))
                .method("POST")
                .timeout(5, TimeUnit.SECONDS)
                .body(content);
            request.send(resultRef::set);
            assertTrue(servletOnDataAvailableLatch.await(5, TimeUnit.SECONDS));
            request.abort(new ArithmeticException());
        }

        assertTrue(errorDoneLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverError.get(), instanceOf(EofException.class));

        await().atMost(5, TimeUnit.SECONDS).until(resultRef::get, not(nullValue()));
        Result result = resultRef.get();
        assertThat(result.getRequestFailure(), instanceOf(ArithmeticException.class));
        assertThat(result.getResponseFailure(), instanceOf(ArithmeticException.class));
        assertThat(result.getResponse().getStatus(), is(0));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRecyclingWhenUsingReader(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Must be a Reader and not an InputStream.
                BufferedReader br = request.getReader();
                while (true)
                {
                    int b = br.read();
                    if (b == -1)
                        break;
                }
                // Paranoid check.
                assertThat(br.read(), is(-1));
            }
        });

        ContentResponse response1 = client.newRequest(newURI(transport))
            .method("POST")
            .timeout(5, TimeUnit.SECONDS)
            .body(new BytesRequestContent(new byte[512]))
            .send();
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));

        // Send a 2nd request to make sure recycling works.
        ContentResponse response2 = client.newRequest(newURI(transport))
            .method("POST")
            .timeout(5, TimeUnit.SECONDS)
            .body(new BytesRequestContent(new byte[512]))
            .send();
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
    }
}

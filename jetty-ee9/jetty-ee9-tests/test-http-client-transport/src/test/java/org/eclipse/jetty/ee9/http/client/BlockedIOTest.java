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

package org.eclipse.jetty.ee9.http.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlockedIOTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBlockingReadThenNormalComplete(Transport transport) throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AtomicReference<Throwable> rereadException = new AtomicReference<>();

        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                new Thread(() ->
                {
                    try
                    {
                        int b = baseRequest.getHttpInput().read();
                        if (b == '1')
                        {
                            started.countDown();
                            if (baseRequest.getHttpInput().read() > Integer.MIN_VALUE)
                                throw new IllegalStateException();
                        }
                    }
                    catch (Throwable ex1)
                    {
                        readException.set(ex1);
                        try
                        {
                            if (baseRequest.getHttpInput().read() > Integer.MIN_VALUE)
                                throw new IllegalStateException();
                        }
                        catch (Throwable ex2)
                        {
                            rereadException.set(ex2);
                        }
                        finally
                        {
                            stopped.countDown();
                        }
                    }
                }).start();

                try
                {
                    // wait for thread to start and read first byte
                    started.await(10, TimeUnit.SECONDS);
                    // give it time to block on second byte
                    Thread.sleep(1000);
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }

                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("OK\r\n");
            }
        });

        AsyncRequestContent requestContent = new AsyncRequestContent();
        CountDownLatch ok = new CountDownLatch(2);
        scenario.client.newRequest(scenario.newURI())
            .method("POST")
            .body(requestContent)
            .onResponseContent((response, content) ->
            {
                assertThat(BufferUtil.toString(content), containsString("OK"));
                ok.countDown();
            })
            .onResponseSuccess(response ->
            {
                try
                {
                    assertThat(response.getStatus(), is(200));
                    stopped.await(10, TimeUnit.SECONDS);
                    ok.countDown();
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                }
            })
            .send(null);
        requestContent.offer(BufferUtil.toBuffer("1"));

        assertTrue(ok.await(10, TimeUnit.SECONDS));
        assertThat(readException.get(), instanceOf(IOException.class));
        assertThat(rereadException.get(), instanceOf(IOException.class));
    }
}

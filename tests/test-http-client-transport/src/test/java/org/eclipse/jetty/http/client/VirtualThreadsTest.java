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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledForJreRange(max = JRE.JAVA_18)
public class VirtualThreadsTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testServletInvokedOnVirtualThread(Transport transport) throws Exception
    {
        // No virtual thread support in FCGI server-side.
        Assumptions.assumeTrue(transport != Transport.FCGI);

        init(transport);
        scenario.prepareServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (!VirtualThreads.isVirtualThread())
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
            }
        });
        ThreadPool threadPool = scenario.server.getThreadPool();
        if (threadPool instanceof VirtualThreads.Configurable)
            ((VirtualThreads.Configurable)threadPool).setUseVirtualThreads(true);
        scenario.server.start();
        scenario.startClient();

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus(), " for transport " + transport);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testServletCallbacksInvokedOnVirtualThread(Transport transport) throws Exception
    {
        // No virtual thread support in FCGI server-side.
        Assumptions.assumeTrue(transport != Transport.FCGI);

        init(transport);
        byte[] data = new byte[128 * 1024 * 1024];
        scenario.prepareServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (!VirtualThreads.isVirtualThread())
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);

                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                ServletOutputStream output = response.getOutputStream();

                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        if (!VirtualThreads.isVirtualThread())
                            throw new IOException("not a virtual thread");
                        while (input.isReady())
                        {
                            int read = input.read();
                            if (read < 0)
                                break;
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        if (!VirtualThreads.isVirtualThread())
                            throw new IOException("not a virtual thread");
                        // Write a large response content to cause onWritePossible() to be called.
                        output.write(data);
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });

                output.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (!VirtualThreads.isVirtualThread())
                            throw new IOException("not a virtual thread");
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });
            }
        });
        ThreadPool threadPool = scenario.server.getThreadPool();
        if (threadPool instanceof VirtualThreads.Configurable)
            ((VirtualThreads.Configurable)threadPool).setUseVirtualThreads(true);
        scenario.server.start();
        scenario.startClient();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger length = new AtomicInteger();
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .body(new StringRequestContent("hello"))
            .onResponseContent((response, content) -> length.addAndGet(content.remaining()))
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertEquals(length.get(), data.length);
    }
}

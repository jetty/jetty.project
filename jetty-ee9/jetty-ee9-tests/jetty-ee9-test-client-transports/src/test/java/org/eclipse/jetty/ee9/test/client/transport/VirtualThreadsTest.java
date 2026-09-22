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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledForJreRange(max = JRE.JAVA_18)
public class VirtualThreadsTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testServletInvokedOnVirtualThread(TransportType transportType) throws Exception
    {
        // No virtual thread support in FCGI server-side.
        Assumptions.assumeTrue(transportType != TransportType.FCGI);

        String virtualThreadsName = "green-";
        prepareServer(transportType, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                if (!VirtualThreads.isVirtualThread())
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
                if (!Thread.currentThread().getName().startsWith(virtualThreadsName))
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
            }
        });
        ThreadPool threadPool = server.getThreadPool();
        if (threadPool instanceof VirtualThreads.Configurable)
        {
            Executor virtualThreadsExecutor = VirtualThreads.getNamedVirtualThreadsExecutor(virtualThreadsName);
            ((VirtualThreads.Configurable)threadPool).setVirtualThreadsExecutor(virtualThreadsExecutor);
        }
        server.start();
        startClient(transportType);

        ContentResponse response = client.newRequest(newURI(transportType))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus(), " for transport " + transportType);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testServletCallbacksInvokedOnVirtualThread(TransportType transportType) throws Exception
    {
        // No virtual thread support in FCGI server-side.
        Assumptions.assumeTrue(transportType != TransportType.FCGI);

        byte[] data = new byte[2 * 1024 * 1024];
        prepareServer(transportType, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
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
                        // Set the WriteListener only after all the request content has been read,
                        // otherwise onWritePossible() may be called before onAllDataRead().
                        output.setWriteListener(new WriteListener()
                        {
                            private boolean written;

                            @Override
                            public void onWritePossible() throws IOException
                            {
                                if (!VirtualThreads.isVirtualThread())
                                    throw new IOException("not a virtual thread");
                                if (!written)
                                {
                                    written = true;
                                    // Write a large response content to cause onWritePossible() to be called again.
                                    output.write(data);
                                }
                                if (output.isReady())
                                    asyncContext.complete();
                            }

                            @Override
                            public void onError(Throwable t)
                            {
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });
            }
        });
        ThreadPool threadPool = server.getThreadPool();
        if (threadPool instanceof VirtualThreads.Configurable)
            ((VirtualThreads.Configurable)threadPool).setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
        server.start();
        startClient(transportType);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger length = new AtomicInteger();
        // Delay the request content, so that the server application
        // returns from service() before the request content arrives.
        AsyncRequestContent requestContent = new AsyncRequestContent();
        client.newRequest(newURI(transportType))
            .method(HttpMethod.POST)
            .body(requestContent)
            .onResponseContent((response, content) -> length.addAndGet(content.remaining()))
            .timeout(20, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                    latch.countDown();
            });

        Thread.sleep(500);
        requestContent.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
        requestContent.close();

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertEquals(data.length, length.get());
    }
}

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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelStateTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void setUp()
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void testCompletedFromOtherThreadDoesNotCloseOutputOfNextRequest() throws Exception
    {
        ContextHandler contextHandler = new ContextHandler("/");
        CountDownLatch firstCompletedLatch = new CountDownLatch(1);

        contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                if (request.getRequestURI().equals("/first"))
                {
                    response.getOutputStream().write("first".getBytes(StandardCharsets.UTF_8));
                }
                else if (request.getRequestURI().equals("/second"))
                {
                    // The next request has started on the recycled HttpChannel, unblock completed().
                    ((BlockableCompletedHttpChannel)baseRequest.getHttpChannel()).nextRequestStarted();
                    // Write once the completion of the first request is done.
                    try
                    {
                        assertTrue(firstCompletedLatch.await(5, TimeUnit.SECONDS));
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                    response.getOutputStream().write("second".getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        server.setHandler(new Handler.Wrapper(contextHandler.getCoreContextHandler())
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                if (request.getHttpURI().getPath().equals("/first"))
                {
                    // Change the HttpChannel implementation for this one that can be blocked in completed().
                    // The HttpChannel is cached, so it is reused by the second request.
                    BlockableCompletedHttpChannel blockableCompletedHttpChannel = new BlockableCompletedHttpChannel(contextHandler, request);
                    request.getComponents().getCache().setAttribute(HttpChannel.class.getName(), blockableCompletedHttpChannel);

                    response = new org.eclipse.jetty.server.Response.Wrapper(request, response)
                    {
                        @Override
                        public void write(boolean last, ByteBuffer byteBuffer, Callback writeCallback)
                        {
                            if (!last)
                            {
                                super.write(false, byteBuffer, writeCallback);
                                return;
                            }

                            // Complete the last write of the first request from another thread,
                            // while the handling thread is still handling the request.
                            super.write(true, byteBuffer, Callback.from(() -> new Thread(() ->
                            {
                                // Succeeding the callback will call completed(), make it block in it.
                                blockableCompletedHttpChannel.blockInCompleted();
                                writeCallback.succeeded();

                                firstCompletedLatch.countDown();
                            }).start(), writeCallback::failed));
                            blockableCompletedHttpChannel.awaitUntilInCompleted(5, TimeUnit.SECONDS);
                        }
                    };
                }
                return super.handle(request, response, callback);
            }
        });
        server.start();

        LocalConnector.LocalEndPoint endPoint = connector.executeRequest("""
            GET /first HTTP/1.1\r
            Host: localhost\r
            \r
            GET /second HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response first = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(first.getStatus(), is(HttpStatus.OK_200));
        assertThat(first.getContent(), is("first"));
        HttpTester.Response second = HttpTester.parseResponse(endPoint.getResponse());
        assertThat(second.getStatus(), is(HttpStatus.OK_200));
        assertThat(second.getContent(), is("second"));
    }

    // An HttpChannel that blocks the thread calling HttpChannelState.completed()
    // at the point where it accesses the Response, which is the window of the race.
    private static class BlockableCompletedHttpChannel extends HttpChannel
    {
        private final AtomicBoolean blockInCompleted = new AtomicBoolean();
        private final CountDownLatch inCompletedLatch = new CountDownLatch(1);
        private final CountDownLatch handledOrNextRequestLatch = new CountDownLatch(1);

        private BlockableCompletedHttpChannel(ContextHandler contextHandler, org.eclipse.jetty.server.Request request)
        {
            super(contextHandler, request.getConnectionMetaData());
        }

        void blockInCompleted()
        {
            blockInCompleted.set(true);
        }

        void awaitUntilInCompleted(long timeout, TimeUnit unit)
        {
            try
            {
                assertTrue(inCompletedLatch.await(timeout, unit));
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        void nextRequestStarted()
        {
            handledOrNextRequestLatch.countDown();
        }

        @Override
        public Response getResponse()
        {
            if (blockInCompleted.compareAndSet(true, false))
            {
                inCompletedLatch.countDown();
                // Wait for the handling thread to either start the next
                // request on this channel (the bug) or to stop handling (the fix).
                // The next request must have started, as the HttpChannel
                // is recycled again when the next request starts.
                try
                {
                    assertTrue(handledOrNextRequestLatch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }
            }
            return super.getResponse();
        }

        @Override
        public boolean handle()
        {
            boolean result = super.handle();
            // If the request is completed, wait for the next request instead.
            if (!isRequestCompleted())
                handledOrNextRequestLatch.countDown();
            return result;
        }
    }
}

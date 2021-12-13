//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelEventTest
{
    private Server server;
    private LocalConnector connector;

    public void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testRequestContentSlice() throws Exception
    {
        int data = 'x';
        CountDownLatch applicationLatch = new CountDownLatch(1);
        start(new TestHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                ServletInputStream input = request.getInputStream();
                int content = input.read();
                assertEquals(data, content);
                applicationLatch.countDown();
            }
        });

        CountDownLatch listenerLatch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onRequestContent(Request request, ByteBuffer content)
            {
                // Consume the buffer to verify it's a slice.
                content.position(content.limit());
                listenerLatch.countDown();
            }
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.setHeader("Host", "localhost");
        request.setContent(new byte[]{(byte)data});

        ByteBuffer buffer = connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Listener event happens before the application.
        assertTrue(listenerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(applicationLatch.await(5, TimeUnit.SECONDS));

        HttpTester.Response response = HttpTester.parseResponse(buffer);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testResponseContentSlice() throws Exception
    {
        byte[] data = new byte[]{'y'};
        start(new TestHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.getOutputStream().write(data);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseContent(Request request, ByteBuffer content)
            {
                assertTrue(content.hasRemaining());
                latch.countDown();
            }
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.setHeader("Host", "localhost");
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.toString(), 5, TimeUnit.SECONDS));

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(data, response.getContentBytes());
    }

    @Test
    public void testRequestFailure() throws Exception
    {
        start(new TestHandler());

        CountDownLatch latch = new CountDownLatch(2);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onRequestFailure(Request request, Throwable failure)
            {
                latch.countDown();
            }

            @Override
            public void onComplete(Request request)
            {
                latch.countDown();
            }
        });

        // No Host header, request will fail.
        String request = HttpTester.newRequest().toString();
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request, 5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResponseFailure() throws Exception
    {
        start(new TestHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                // Closes all connections, response will fail.
                connector.getConnectedEndPoints().forEach(EndPoint::close);
            }
        });

        CountDownLatch latch = new CountDownLatch(2);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseFailure(Request request, Throwable failure)
            {
                latch.countDown();
            }

            @Override
            public void onComplete(Request request)
            {
                latch.countDown();
            }
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.setHeader("Host", "localhost");
        HttpTester.parseResponse(connector.getResponse(request.toString(), 5, TimeUnit.SECONDS));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testExchangeTimeRecording() throws Exception
    {
        start(new TestHandler());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong elapsed = new AtomicLong();
        connector.addBean(new HttpChannel.Listener()
        {
            private final String attribute = getClass().getName() + ".begin";

            @Override
            public void onRequestBegin(Request request)
            {
                request.setAttribute(attribute, System.nanoTime());
            }

            @Override
            public void onComplete(Request request)
            {
                long endTime = System.nanoTime();
                long beginTime = (Long)request.getAttribute(attribute);
                elapsed.set(endTime - beginTime);
                latch.countDown();
            }
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.setHeader("Host", "localhost");
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.toString(), 5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(elapsed.get(), Matchers.greaterThan(0L));
    }

    @Test
    public void testTransientListener() throws Exception
    {
        start(new TestHandler());

        CountDownLatch latch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.TransientListeners());
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onRequestBegin(Request request)
            {
                request.getHttpChannel().addListener(new HttpChannel.Listener()
                {
                    @Override
                    public void onComplete(Request request)
                    {
                        latch.countDown();
                    }
                });
            }
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.setHeader("Host", "localhost");
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.toString(), 5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private static class TestHandler extends AbstractHandler
    {
        @Override
        public final void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            jettyRequest.setHandled(true);
            handle(request, response);
        }

        protected void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
        }
    }
}

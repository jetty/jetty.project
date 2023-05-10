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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
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
        byte data = 'x';
        CountDownLatch applicationLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                Content.Chunk chunk = request.read();
                assertEquals(data, chunk.getByteBuffer().get());
                applicationLatch.countDown();
                return true;
            }
        });

        CountDownLatch listenerLatch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onRequestContent(Request request, Response response, ByteBuffer content)
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
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                response.write(true, ByteBuffer.wrap(data), callback);
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseContent(Request request, Response response, ByteBuffer content)
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
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(2);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onRequestFailure(Request request, Response response, Throwable failure)
            {
                latch.countDown();
            }

            @Override
            public void onComplete(Request request, Response response)
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
    public void testResponseBeginModifyHeaders() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
                // Intentionally add two values for a header
                response.getHeaders().put("X-Header", "foo");
                response.getHeaders().put("X-Header", "bar");
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseBegin(Request request, Response response)
            {
                // Eliminate all "X-Header" values from Handler, and force it to be the one value "zed"
                response.getHeaders().computeField("X-Header", (n, f) -> new HttpField(n, "zed"));
            }

            @Override
            public void onComplete(Request request, Response response)
            {
                latch.countDown();
            }
        });

        HttpTester.Request request = HttpTester.newRequest();
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "localhost");
        request.setHeader("Connection", "close");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.toString(), 5, TimeUnit.SECONDS));

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<HttpField> xheaders = response.getFields("X-Header");
        assertThat("X-Header count", xheaders.size(), is(1));
        assertThat("X-Header[0].value", xheaders.get(0).getValue(), is("zed"));
    }

    @Test
    public void testResponseFailure() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws IOException
            {
                // Closes all connections, response will fail.
                connector.getConnectedEndPoints().forEach(EndPoint::close);
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(2);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseFailure(Request request, Response response, Throwable failure)
            {
                latch.countDown();
            }

            @Override
            public void onComplete(Request request, Response response)
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
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong elapsed = new AtomicLong();
        connector.addBean(new HttpChannel.Listener()
        {
            private final String attribute = getClass().getName() + ".begin";

            @Override
            public void onRequestBegin(Request request, Response response)
            {
                request.setAttribute(attribute, NanoTime.now());
            }

            @Override
            public void onComplete(Request request, Response response)
            {
                long beginTime = (Long)request.getAttribute(attribute);
                elapsed.set(NanoTime.since(beginTime));
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
}

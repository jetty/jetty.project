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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    public void dispose()
    {
        LifeCycle.stop(server);
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
                while (!chunk.isLast())
                {
                    if (chunk.hasRemaining())
                    {
                        assertEquals(data, chunk.getByteBuffer().get());
                    }
                    chunk = request.read();
                }

                applicationLatch.countDown();
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch listenerLatch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onRequestRead(Request request, Content.Chunk chunk)
            {
                // attempt to consume the byte buffer (TODO: should we make this chunk read-only? a slice?)
                ByteBuffer buffer = chunk.getByteBuffer();
                buffer.position(buffer.limit());
                listenerLatch.countDown();
            }
        });

        String rawRequest = """
            GET / HTTP/1.1
            Host: localhost
            Connection: close
            Content-Length: 1
            
            x
            """;

        String rawResponse = connector.getResponse(rawRequest, 5, TimeUnit.SECONDS);

        // Listener event happens before the application.
        assertTrue(listenerLatch.await(5, TimeUnit.SECONDS));
        assertTrue(applicationLatch.await(5, TimeUnit.SECONDS));

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    @Disabled("re-enable once PR #9684 is merged")
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
        AtomicLong contentSeenLength = new AtomicLong();
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseWrite(Request request, boolean last, ByteBuffer content, Throwable failure)
            {
                contentSeenLength.addAndGet(content.remaining());
                if (last || failure != null)
                    latch.countDown();
            }
        });

        String rawRequest = """
            GET / HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        String rawResponse = connector.getResponse(rawRequest, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, contentSeenLength.get());

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
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(2);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onComplete(Request request, Throwable failure)
            {
                latch.countDown();
            }
        });

        // No Host header, request will fail.
        String rawRequest = """
            GET / HTTP/1.1
            Connection: close
            
            """;

        String rawResponse = connector.getResponse(rawRequest, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

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
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onResponseCommitted(Request request, int status, HttpFields response)
            {
                // Eliminate all "X-Header" values from Handler, and force it to be the one value "zed"
                // TODO: response.computeField("X-Header", (n, f) -> new HttpField(n, "zed"));
            }

            @Override
            public void onComplete(Request request, Throwable failure)
            {
                latch.countDown();
            }
        });

        String rawRequest = """
            GET / HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        String rawResponse = connector.getResponse(rawRequest, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

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
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Throwable> seenFailure = new AtomicReference<>();
        connector.addBean(new HttpChannel.Listener()
        {
            @Override
            public void onComplete(Request request, Throwable failure)
            {
                seenFailure.set(failure);
                latch.countDown();
            }
        });

        String rawRequest = """
            GET / HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        String rawResponse = connector.getResponse(rawRequest, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(seenFailure.get());
    }

    @Test
    public void testExchangeTimeRecording() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                callback.succeeded();
                return true;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong elapsed = new AtomicLong();
        connector.addBean(new HttpChannel.Listener()
        {
            private final String attribute = getClass().getName() + ".begin";

            @Override
            public void onRequestBegin(Request request)
            {
                request.setAttribute(attribute, NanoTime.now());
            }

            @Override
            public void onComplete(Request request, Throwable failure)
            {
                long beginTime = (Long)request.getAttribute(attribute);
                elapsed.set(NanoTime.since(beginTime));
                latch.countDown();
            }
        });

        String rawRequest = """
            GET / HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        String rawResponse = connector.getResponse(rawRequest, 5, TimeUnit.SECONDS);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertThat(elapsed.get(), Matchers.greaterThan(0L));
    }
}

//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.http;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpReceiver;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.log.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpReceiverOverHTTPTest
{
    private HttpClient client;
    private HttpDestinationOverHTTP destination;
    private ByteArrayEndPoint endPoint;
    private HttpConnectionOverHTTP connection;

    public static Stream<Arguments> complianceModes()
    {
        return Stream.of(
            HttpCompliance.LEGACY,
            HttpCompliance.RFC2616_LEGACY,
            HttpCompliance.RFC7230_LEGACY
        ).map(Arguments::of);
    }

    public void init(HttpCompliance compliance) throws Exception
    {
        init(compliance, null);
    }

    public void init(HttpCompliance compliance, ByteBufferPool pool) throws Exception
    {
        client = new HttpClient();
        client.setHttpCompliance(compliance);
        if (pool != null)
            client.setByteBufferPool(pool);
        client.start();
        destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", 8080));
        destination.start();
        endPoint = new ByteArrayEndPoint();
        connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<>());
        endPoint.setConnection(connection);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        client.stop();
    }

    protected HttpExchange newExchange()
    {
        HttpRequest request = (HttpRequest)client.newRequest("http://localhost");
        FutureResponseListener listener = new FutureResponseListener(request);
        HttpExchange exchange = new HttpExchange(destination, request, Collections.<Response.ResponseListener>singletonList(listener));
        boolean associated = connection.getHttpChannel().associate(exchange);
        assertTrue(associated);
        exchange.requestComplete(null);
        exchange.terminateRequest();
        return exchange;
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    public void testReceiveNoResponseContent(HttpCompliance compliance) throws Exception
    {
        init(compliance);
        endPoint.addInput(
            "HTTP/1.1 200 OK\r\n" +
                "Content-length: 0\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getReason());
        assertSame(HttpVersion.HTTP_1_1, response.getVersion());
        HttpFields headers = response.getHeaders();
        assertNotNull(headers);
        assertEquals(1, headers.size());
        assertEquals("0", headers.get(HttpHeader.CONTENT_LENGTH));
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    public void testReceiveResponseContent(HttpCompliance compliance) throws Exception
    {
        init(compliance);
        String content = "0123456789ABCDEF";
        endPoint.addInput(
            "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + content.length() + "\r\n" +
                "\r\n" +
                content);
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals("OK", response.getReason());
        assertSame(HttpVersion.HTTP_1_1, response.getVersion());
        HttpFields headers = response.getHeaders();
        assertNotNull(headers);
        assertEquals(1, headers.size());
        assertEquals(String.valueOf(content.length()), headers.get(HttpHeader.CONTENT_LENGTH));
        String received = listener.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(content, received);
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    public void testReceiveResponseContentEarlyEOF(HttpCompliance compliance) throws Exception
    {
        init(compliance);
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        endPoint.addInput(
            "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1);
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();
        endPoint.addInputEOF();
        connection.getHttpChannel().receive();

        ExecutionException e = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
        assertThat(e.getCause(), instanceOf(EOFException.class));
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    public void testReceiveResponseContentIdleTimeout(HttpCompliance compliance) throws Exception
    {
        init(compliance);
        endPoint.addInput(
            "HTTP/1.1 200 OK\r\n" +
                "Content-length: 1\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();
        // ByteArrayEndPoint has an idle timeout of 0 by default,
        // so to simulate an idle timeout is enough to wait a bit.
        Thread.sleep(100);
        connection.onIdleExpired();

        ExecutionException e = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
        assertThat(e.getCause(), instanceOf(TimeoutException.class));
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    public void testReceiveBadResponse(HttpCompliance compliance) throws Exception
    {
        init(compliance);
        endPoint.addInput(
            "HTTP/1.1 200 OK\r\n" +
                "Content-length: A\r\n" +
                "\r\n");
        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        ExecutionException e = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
        assertThat(e.getCause(), instanceOf(HttpResponseException.class));
        assertThat(e.getCause().getCause(), instanceOf(BadMessageException.class));
        assertThat(e.getCause().getCause().getCause(), instanceOf(NumberFormatException.class));
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    public void testFillInterestedRacingWithBufferRelease(HttpCompliance compliance) throws Exception
    {
        init(compliance);
        connection = new HttpConnectionOverHTTP(endPoint, destination, new Promise.Adapter<>())
        {
            @Override
            protected HttpChannelOverHTTP newHttpChannel()
            {
                return new HttpChannelOverHTTP(this)
                {
                    @Override
                    protected HttpReceiverOverHTTP newHttpReceiver()
                    {
                        return new HttpReceiverOverHTTP(this)
                        {
                            @Override
                            protected void fillInterested()
                            {
                                // Verify that the buffer has been released
                                // before fillInterested() is called.
                                assertNull(getResponseBuffer());
                                // Fill the endpoint so receive is called again.
                                endPoint.addInput("X");
                                super.fillInterested();
                            }
                        };
                    }
                };
            }
        };
        endPoint.setConnection(connection);

        // Partial response to trigger the call to fillInterested().
        endPoint.addInput(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "\r\n");

        HttpExchange exchange = newExchange();
        FutureResponseListener listener = (FutureResponseListener)exchange.getResponseListeners().get(0);
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("complianceModes")
    void testReceiveReentrance(HttpCompliance compliance) throws Exception
    {
        boolean debugEnabled = Log.getLogger(HttpReceiver.class).isDebugEnabled();
        Log.getLogger(HttpReceiver.class).setDebugEnabled(true);
        try
        {
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            init(compliance, new ByteBufferPool()
            {
                @Override
                public ByteBuffer acquire(int size, boolean direct)
                {
                    String response =
                        "HTTP/1.1 200 OK\r\n" +
                            "\r\n";
                    return ByteBuffer.wrap(response.getBytes(StandardCharsets.ISO_8859_1));
                }

                @Override
                public void release(ByteBuffer buffer)
                {
                }
            });
            HttpChannelOverHTTP httpChannelOverHTTP = new HttpChannelOverHTTP(connection);
            HttpReceiverOverHTTP httpReceiverOverHTTP = new HttpReceiverOverHTTP(httpChannelOverHTTP)
            {
                @Override
                protected boolean responseFailure(Throwable failure)
                {
                    failureRef.set(failure);
                    return super.responseFailure(failure);
                }

                @Override
                public boolean headerComplete()
                {
                    // We are testing this particular order:
                    //  1) thread 1 arrives here first from a call to receive() then gets preempted
                    //  2) thread 2 arrives here second from a call to receive() then returns all the way back
                    //  3) thread 1 resumes and return all the way back

                    Thread t2 = new Thread(this::receive);
                    t2.start();

                    try
                    {
                        t2.join();
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                    return true;
                }
            };

            // start thread 1 receive() call from here
            httpReceiverOverHTTP.receive();

            // make sure no exception was reported
            assertThat(failureRef.get(), nullValue());
        }
        finally
        {
            Log.getLogger(HttpReceiver.class).setDebugEnabled(debugEnabled);
        }
    }
}

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

package org.eclipse.jetty.client.http;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.HttpExchange;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.internal.HttpChannelOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.transport.internal.HttpReceiverOverHTTP;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpReceiverOverHTTPTest
{
    private HttpClient client;
    private HttpDestination destination;
    private ByteArrayEndPoint endPoint;
    private HttpConnectionOverHTTP connection;

    public static Stream<Arguments> complianceModes()
    {
        return Stream.of(
            HttpCompliance.RFC7230,
            HttpCompliance.RFC2616,
            HttpCompliance.LEGACY,
            HttpCompliance.RFC2616_LEGACY,
            HttpCompliance.RFC7230_LEGACY
        ).map(Arguments::of);
    }

    public void init(HttpCompliance compliance) throws Exception
    {
        client = new HttpClient();
        client.setHttpCompliance(compliance);
        client.start();
        destination = new HttpDestination(client, new Origin("http", "localhost", 8080), false);
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

    protected FutureResponseListener startExchange()
    {
        HttpRequest request = (HttpRequest)client.newRequest("http://localhost");
        FutureResponseListener listener = new FutureResponseListener(request);
        request.getResponseListeners().addListener(listener);
        HttpExchange exchange = new HttpExchange(destination, request);
        boolean associated = connection.getHttpChannel().associate(exchange);
        assertTrue(associated);
        exchange.requestComplete(null);
        exchange.terminateRequest();
        return listener;
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
        FutureResponseListener listener = startExchange();
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
        FutureResponseListener listener = startExchange();
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
        FutureResponseListener listener = startExchange();
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
        FutureResponseListener listener = startExchange();
        connection.getHttpChannel().receive();
        // ByteArrayEndPoint has an idle timeout of 0 by default,
        // so to simulate an idle timeout is enough to wait a bit.
        Thread.sleep(100);
        TimeoutException timeoutException = new TimeoutException();
        connection.onIdleExpired(timeoutException);

        ExecutionException e = assertThrows(ExecutionException.class, () -> listener.get(5, TimeUnit.SECONDS));
        assertThat(e.getCause(), instanceOf(TimeoutException.class));
        assertThat(e.getCause(), sameInstance(timeoutException));
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
        FutureResponseListener listener = startExchange();
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

        FutureResponseListener listener = startExchange();
        connection.getHttpChannel().receive();

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());
    }
}

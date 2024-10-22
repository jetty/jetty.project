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

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EventsHandlerTest
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void setUp() throws Exception
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

    private void startServer(Handler handler) throws Exception
    {
        server.setHandler(handler);
        server.start();
    }

    @Test
    public void testRequestAttributesAreMutable() throws Exception
    {
        AtomicReference<String> attribute = new AtomicReference<>();
        EventsHandler eventsHandler = new EventsHandler(new EchoHandler())
        {
            static final String ATTRIBUTE_NAME = EventsHandlerTest.class.getName();

            @Override
            protected void onBeforeHandling(Request request)
            {
                request.setAttribute(ATTRIBUTE_NAME, "testModifyRequestAttributes-1");
            }

            @Override
            protected void onAfterHandling(Request request, boolean handled, Throwable failure)
            {
                request.setAttribute(ATTRIBUTE_NAME, request.getAttribute(ATTRIBUTE_NAME) + "2");
            }

            @Override
            protected void onResponseBegin(Request request, int status, HttpFields headers)
            {
                request.setAttribute(ATTRIBUTE_NAME, request.getAttribute(ATTRIBUTE_NAME) + "3");
            }

            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                attribute.set((String)request.getAttribute(ATTRIBUTE_NAME));
            }
        };

        startServer(eventsHandler);

        String rawRequest = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(rawRequest);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        await().atMost(5, TimeUnit.SECONDS).until(attribute::get, is("testModifyRequestAttributes-123"));
    }

    @Test
    public void testNanoTimestamps() throws Exception
    {
        AtomicReference<Long> beginNanoTime = new AtomicReference<>();
        AtomicReference<Long> readyNanoTime = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        EventsHandler eventsHandler = new EventsHandler(new EchoHandler())
        {
            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                beginNanoTime.set(request.getBeginNanoTime());
                readyNanoTime.set(request.getHeadersNanoTime());
                latch.countDown();
            }
        };
        startServer(eventsHandler);

        String reqLine = "POST / HTTP/1.1\r\n";
        String headers = """
            Host: localhost\r
            Content-length: 6\r
            Content-type: application/octet-stream\r
            Connection: close\r
            \r
            """;
        String body = "ABCDEF";

        try (LocalConnector.LocalEndPoint endPoint = connector.connect())
        {
            endPoint.addInput(reqLine);
            Thread.sleep(500);
            endPoint.addInput(headers);
            Thread.sleep(500);
            endPoint.addInput(body);
            String response = endPoint.getResponse();

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(NanoTime.millisSince(beginNanoTime.get()), greaterThan(900L));
            assertThat(NanoTime.millisSince(readyNanoTime.get()), greaterThan(450L));
        }
    }

    @Test
    public void testEventsOfNoopHandler() throws Exception
    {
        List<String> events = new CopyOnWriteArrayList<>();

        EventsHandler eventsHandler = new EventsHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        })
        {
            @Override
            protected void onRequestRead(Request request, Content.Chunk chunk)
            {
                events.add("onRequestRead");
            }

            @Override
            protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
            {
                events.add("onResponseWrite");
            }

            @Override
            protected void onResponseWriteComplete(Request request, Throwable failure)
            {
                events.add("onResponseWriteComplete");
            }

            @Override
            protected void onResponseTrailersComplete(Request request, HttpFields trailers)
            {
                events.add("onResponseTrailersComplete");
            }

            @Override
            protected void onBeforeHandling(Request request)
            {
                events.add("onBeforeHandling");
            }

            @Override
            protected void onAfterHandling(Request request, boolean handled, Throwable failure)
            {
                events.add("onAfterHandling");
            }

            @Override
            protected void onResponseBegin(Request request, int status, HttpFields headers)
            {
                events.add("onResponseBegin");
            }

            @Override
            protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
            {
                events.add("onComplete");
            }
        };

        startServer(eventsHandler);

        String rawRequest = """
            GET / HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(rawRequest);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(events, equalTo(Arrays.asList("onBeforeHandling", "onAfterHandling", "onResponseBegin", "onComplete"))
        ));
    }
}

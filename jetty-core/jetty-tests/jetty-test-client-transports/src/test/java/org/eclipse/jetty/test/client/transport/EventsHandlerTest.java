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

package org.eclipse.jetty.test.client.transport;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.EventsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.fail;

public class EventsHandlerTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testEventsBufferAndChunkAreReadOnly(Transport transport) throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(EventsHandler.class))
        {
            List<Throwable> onRequestReadExceptions = new CopyOnWriteArrayList<>();
            List<Throwable> onResponseWriteExceptions = new CopyOnWriteArrayList<>();
            EventsHandler eventsHandler = new EventsHandler(new EchoHandler())
            {
                @Override
                protected void onRequestRead(Request request, Content.Chunk chunk)
                {
                    try
                    {
                        if (chunk != null)
                        {
                            chunk.getByteBuffer().put((byte)0);
                        }
                    }
                    catch (ReadOnlyBufferException e)
                    {
                        onRequestReadExceptions.add(e);
                        throw e;
                    }
                    if (chunk != null)
                        chunk.skip(chunk.remaining());
                }

                @Override
                protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
                {
                    try
                    {
                        if (content != null)
                            content.put((byte)0);
                    }
                    catch (ReadOnlyBufferException e)
                    {
                        onResponseWriteExceptions.add(e);
                        throw e;
                    }
                }
            };
            startServer(transport, eventsHandler);
            startClient(transport);

            ContentResponse response = client.POST(newURI(transport))
                .body(new StringRequestContent("ABCDEF"))
                .send();

            assertThat(response.getStatus(), is(200));
            assertThat(response.getContentAsString(), is("ABCDEF"));
            assertThat(onRequestReadExceptions.size(), greaterThan(0));
            assertThat(onResponseWriteExceptions.size(), greaterThan(0));
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testMultipleEventsHandlerChaining(Transport transport) throws Exception
    {
        String longString = "A".repeat(65536);

        StringBuffer innerStringBuffer = new StringBuffer();
        EventsHandler innerEventsHandler = new EventsHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(longString.getBytes(StandardCharsets.US_ASCII)), callback);
                return true;
            }
        })
        {
            @Override
            protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
            {
                if (content != null)
                    innerStringBuffer.append(BufferUtil.toString(content));
            }
        };
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(innerEventsHandler);
        AtomicInteger outerBytesCounter = new AtomicInteger();
        EventsHandler outerEventsHandler = new EventsHandler(gzipHandler)
        {
            @Override
            protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
            {
                if (content != null)
                    outerBytesCounter.addAndGet(content.remaining());
            }
        };
        startServer(transport, outerEventsHandler);
        startClient(transport);

        ContentResponse response = client.GET(newURI(transport));
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is(longString));
        assertThat(innerStringBuffer.toString(), is(longString));
        assertThat(outerBytesCounter.get(), both(greaterThan(0)).and(lessThan(longString.length())));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testWriteNullBuffer(Transport transport) throws Exception
    {
        StringBuffer stringBuffer = new StringBuffer();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        EventsHandler eventsHandler = new EventsHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.write(false, ByteBuffer.wrap("ABCDEF".getBytes(StandardCharsets.US_ASCII)),
                    Callback.from(() -> response.write(false, null,
                        Callback.from(() -> response.write(true, null, callback), callback::failed))));
                return true;
            }
        })
        {
            @Override
            protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
            {
                if (content != null)
                    stringBuffer.append(BufferUtil.toString(content));
            }

            @Override
            protected void onResponseWriteComplete(Request request, Throwable failure)
            {
                if (failure != null)
                    failures.add(failure);
            }
        };
        startServer(transport, eventsHandler);
        startClient(transport);

        ContentResponse response = client.GET(newURI(transport));
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("ABCDEF"));
        assertThat(stringBuffer.toString(), is("ABCDEF"));
        await().atMost(5, TimeUnit.SECONDS).during(1, TimeUnit.SECONDS).until(failures::size, is(0));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUsingEventsResponseAsContentSourceFails(Transport transport) throws Exception
    {
        TestForbiddenMethodsEventsHandler eventsHandler = new TestForbiddenMethodsEventsHandler(new EchoHandler());
        startServer(transport, eventsHandler);
        startClient(transport);

        ContentResponse response = client.POST(newURI(transport))
            .body(new StringRequestContent("ABCDEF"))
            .send();

        assertThat(response.getStatus(), is(200));
        switch (transport)
        {
            // Two reads, maybe one null read, two writes, two writes complete.
            case HTTP:
            case HTTPS:
            case FCGI:
                await().atMost(5, TimeUnit.SECONDS).until(() -> eventsHandler.exceptions.size() / 4, allOf(greaterThanOrEqualTo(10), lessThanOrEqualTo(11)));
                break;
            // One read, maybe one null read, one write, one write complete.
            case H2:
            case H2C:
            case H3:
                await().atMost(5, TimeUnit.SECONDS).until(() -> eventsHandler.exceptions.size() / 4, allOf(greaterThanOrEqualTo(7), lessThanOrEqualTo(8)));
                break;
            default:
                fail("Missing assertion for transport " + transport);
        }
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUsingEventsResponseAsContentSourceFailsWithTrailers(Transport transport) throws Exception
    {
        TestForbiddenMethodsEventsHandler eventsHandler = new TestForbiddenMethodsEventsHandler(new EchoHandler());
        startServer(transport, eventsHandler);
        startClient(transport);

        AtomicInteger status = new AtomicInteger();
        AsyncRequestContent asyncRequestContent = new AsyncRequestContent();
        CountDownLatch latch = new CountDownLatch(1);
        client.POST(newURI(transport))
            .body(asyncRequestContent)
            .trailersSupplier(() -> HttpFields.build().put("Extra-Stuff", "xyz"))
            .send(result ->
            {
                status.set(result.getResponse().getStatus());
                latch.countDown();
            });
        asyncRequestContent.write(ByteBuffer.wrap("ABCDEF".getBytes(StandardCharsets.US_ASCII)), Callback.NOOP);
        asyncRequestContent.close();

        assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
        assertThat(status.get(), is(200));
        await().atMost(5, TimeUnit.SECONDS).until(() -> eventsHandler.exceptions.size() / 4, allOf(greaterThanOrEqualTo(10), lessThanOrEqualTo(12)));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDelayedEvents(Transport transport) throws Exception
    {
        TestEventsRecordingHandler eventsHandler = new TestEventsRecordingHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Fields query = Request.extractQueryParameters(request);
                sleep(query, "handling");
                new Thread(() ->
                {
                    sleep(query, "succeeding");
                    callback.succeeded();
                }).start();
                return true;
            }

            private void sleep(Fields query, String fieldName)
            {
                Fields.Field delayField = query.get(fieldName);
                if (delayField == null)
                    return;
                long delay = Long.parseLong(delayField.getValue());
                try
                {
                    Thread.sleep(delay);
                }
                catch (InterruptedException e)
                {
                    // ignore
                }
            }
        });
        startServer(transport, eventsHandler);
        startClient(transport);

        URI uri = URI.create(newURI(transport).toASCIIString() + "?handling=500&succeeding=500");

        ContentResponse response = client.GET(uri);
        assertThat(response.getStatus(), is(200));

        await().atMost(1, TimeUnit.SECONDS).until(() -> eventsHandler.getEvents().size(), is(4));
        assertThat(eventsHandler.getEvents().get(0).name, equalTo("onBeforeHandling"));
        assertThat(eventsHandler.getEvents().get(0).delayInNs, greaterThan(0L));
        assertThat(eventsHandler.getEvents().get(1).name, equalTo("onAfterHandling"));
        assertThat(eventsHandler.getEvents().get(1).delayInNs - eventsHandler.getEvents().get(0).delayInNs, both(greaterThan(500_000_000L)).and(lessThan(600_000_000L)));
        assertThat(eventsHandler.getEvents().get(2).name, equalTo("onResponseBegin"));
        assertThat(eventsHandler.getEvents().get(2).delayInNs - eventsHandler.getEvents().get(1).delayInNs, both(greaterThan(500_000_000L)).and(lessThan(600_000_000L)));
        assertThat(eventsHandler.getEvents().get(3).name, equalTo("onComplete"));
        assertThat(eventsHandler.getEvents().get(3).delayInNs - eventsHandler.getEvents().get(2).delayInNs, greaterThan(0L));
    }

    private static class TestEventsRecordingHandler extends EventsHandler
    {
        private final long begin;
        private final List<Event> events = new CopyOnWriteArrayList<>();

        public TestEventsRecordingHandler(Handler handler)
        {
            super(handler);
            this.begin = NanoTime.now();
        }

        private void addEvent(String name)
        {
            events.add(new Event(name, NanoTime.since(begin)));
        }

        public List<Event> getEvents()
        {
            return events;
        }

        @Override
        protected void onBeforeHandling(Request request)
        {
            addEvent("onBeforeHandling");
        }

        @Override
        protected void onRequestRead(Request request, Content.Chunk chunk)
        {
            addEvent("onRequestRead");
        }

        @Override
        protected void onAfterHandling(Request request, boolean handled, Throwable failure)
        {
            addEvent("onAfterHandling");
        }

        @Override
        protected void onResponseBegin(Request request, int status, HttpFields headers)
        {
            addEvent("onResponseBegin");
        }

        @Override
        protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
        {
            addEvent("onResponseWrite");
        }

        @Override
        protected void onResponseWriteComplete(Request request, Throwable failure)
        {
            addEvent("onResponseWriteComplete");
        }

        @Override
        protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
        {
            addEvent("onComplete");
        }

        record Event(String name, long delayInNs)
        {
        }
    }

    private static class TestForbiddenMethodsEventsHandler extends EventsHandler
    {
        private final List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        public TestForbiddenMethodsEventsHandler(Handler handler)
        {
            super(handler);
        }

        @Override
        protected void onBeforeHandling(Request request)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onRequestRead(Request request, Content.Chunk chunk)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onAfterHandling(Request request, boolean handled, Throwable failure)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onResponseBegin(Request request, int status, HttpFields headers)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onResponseWrite(Request request, boolean last, ByteBuffer content)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onResponseWriteComplete(Request request, Throwable failure)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onResponseTrailersComplete(Request request, HttpFields trailers)
        {
            useForbiddenMethods(request, exceptions);
        }

        @Override
        protected void onComplete(Request request, int status, HttpFields headers, Throwable failure)
        {
            useForbiddenMethods(request, exceptions);
        }

        private static void useForbiddenMethods(Request request, List<Throwable> exceptions)
        {
            try
            {
                request.read();
            }
            catch (Throwable x)
            {
                exceptions.add(x);
            }
            try
            {
                request.demand(() -> {});
            }
            catch (Throwable x)
            {
                exceptions.add(x);
            }
            try
            {
                request.fail(new Throwable());
            }
            catch (Throwable x)
            {
                exceptions.add(x);
            }
            try
            {
                request.addHttpStreamWrapper(httpStream -> null);
            }
            catch (Throwable x)
            {
                exceptions.add(x);
            }
        }
    }
}

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

package org.eclipse.jetty.http2.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.Trailers;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrailersTest extends AbstractTest
{
    @Test
    public void testTrailersSentByClient() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                assertFalse(frame.isEndStream());
                assertTrue(request.getFields().contains("X-Request"));
                return new Stream.Listener()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        MetaData trailer = frame.getMetaData();
                        assertTrue(frame.isEndStream());
                        assertTrue(trailer.getFields().contains("X-Trailer"));
                        latch.countDown();
                    }
                };
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        HttpFields.Mutable requestFields = HttpFields.build();
        requestFields.put("X-Request", "true");
        MetaData.Request request = newRequest("GET", requestFields);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, null);
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        // Send the trailers.
        HttpFields.Mutable trailerFields = HttpFields.build();
        trailerFields.put("X-Trailer", "true");
        MetaData trailers = new MetaData(HttpVersion.HTTP_2, trailerFields);
        HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailers, null, true);
        stream.headers(trailerFrame, Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHandlerRequestTrailers() throws Exception
    {
        CountDownLatch trailerLatch = new CountDownLatch(1);
        start(new Handler.Processor()
        {
            private Request _request;
            private Callback _callback;

            @Override
            public void process(Request request, Response response, Callback callback)
            {
                _request = request;
                _callback = callback;
                request.demand(this::firstRead);
            }

            private void firstRead()
            {
                Content.Chunk chunk = _request.read();

                // No trailers yet.
                assertThat(chunk, not(instanceOf(Trailers.class)));

                trailerLatch.countDown();

                _request.demand(this::otherReads);
            }

            private void otherReads()
            {
                while (true)
                {
                    Content.Chunk chunk = _request.read();
                    if (chunk == null)
                    {
                        _request.demand(this::otherReads);
                        return;
                    }
                    if (chunk instanceof Trailers contentTrailers)
                    {
                        HttpFields trailers = contentTrailers.getTrailers();
                        assertNotNull(trailers.get("X-Trailer"));
                        _callback.succeeded();
                    }
                }
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        HttpFields.Mutable requestFields = HttpFields.build();
        requestFields.put("X-Request", "true");
        MetaData.Request request = newRequest("GET", requestFields);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, streamPromise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        // Send some data.
        Callback.Completable callback = new Callback.Completable();
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), false), callback);

        assertTrue(trailerLatch.await(555, TimeUnit.SECONDS));

        // Send the trailers.
        callback.thenRun(() ->
        {
            HttpFields.Mutable trailerFields = HttpFields.build();
            trailerFields.put("X-Trailer", "true");
            MetaData trailers = new MetaData(HttpVersion.HTTP_2, trailerFields);
            HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailers, null, true);
            stream.headers(trailerFrame, Callback.NOOP);
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testTrailersSentByServer() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HttpFields.Mutable responseFields = HttpFields.build();
                responseFields.put("X-Response", "true");
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, responseFields);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, false);
                stream.headers(responseFrame, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        HttpFields.Mutable trailerFields = HttpFields.build();
                        trailerFields.put("X-Trailer", "true");
                        MetaData trailer = new MetaData(HttpVersion.HTTP_2, trailerFields);
                        HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailer, null, true);
                        stream.headers(trailerFrame, NOOP);
                    }
                });
                return null;
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener()
        {
            private boolean responded;

            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (!responded)
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertTrue(response.getFields().contains("X-Response"));
                    assertFalse(frame.isEndStream());
                    responded = true;
                }
                else
                {
                    MetaData trailer = frame.getMetaData();
                    assertTrue(trailer.getFields().contains("X-Trailer"));
                    assertTrue(frame.isEndStream());
                    latch.countDown();
                }
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testTrailersSentByServerShouldNotSendEmptyDataFrame() throws Exception
    {
        String trailerName = "X-Trailer";
        String trailerValue = "Zot!";
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                HttpFields.Mutable trailers = response.getOrCreateTrailers();
                Content.Sink.write(response, false, UTF_8.encode("hello_trailers"));
                // Force the content to be sent above, and then only send the trailers below.
                trailers.put(trailerName, trailerValue);
                callback.succeeded();
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        List<Frame> frames = new ArrayList<>();
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                frames.add(frame);
                if (frame.isEndStream())
                    latch.countDown();
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                frames.add(data.frame());
                data.release();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(3, frames.size(), frames.toString());

        HeadersFrame headers = (HeadersFrame)frames.get(0);
        DataFrame data = (DataFrame)frames.get(1);
        HeadersFrame trailers = (HeadersFrame)frames.get(2);

        assertFalse(headers.isEndStream());
        assertFalse(data.isEndStream());
        assertTrue(trailers.isEndStream());
        assertEquals(trailers.getMetaData().getFields().get(trailerName), trailerValue);
    }

    @Test
    public void testRequestTrailerInvalidHpackSent() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(requestFrame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.wrap(StringUtil.getUtf8Bytes("hello"));
        Callback.Completable completable = new Callback.Completable();
        stream.data(new DataFrame(stream.getId(), data, false), completable);
        CountDownLatch failureLatch = new CountDownLatch(1);
        completable.thenRun(() ->
        {
            // Invalid trailer: cannot contain pseudo headers.
            HttpFields.Mutable trailerFields = HttpFields.build();
            trailerFields.put(HttpHeader.C_METHOD, "GET");
            MetaData trailer = new MetaData(HttpVersion.HTTP_2, trailerFields);
            HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailer, null, true);
            stream.headers(trailerFrame, Callback.from(Callback.NOOP::succeeded, x -> failureLatch.countDown()));
        });
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestTrailerInvalidHpackReceived() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback) throws Exception
            {
                try
                {
                    Content.Source.consumeAll(request);
                }
                catch (IOException x)
                {
                    serverLatch.countDown();
                    throw x;
                }
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(requestFrame, promise, new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                clientLatch.countDown();
                callback.succeeded();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.wrap(StringUtil.getUtf8Bytes("hello"));
        Callback.Completable completable = new Callback.Completable();
        stream.data(new DataFrame(stream.getId(), data, false), completable);
        completable.thenRun(() ->
        {
            // Disable checks for invalid headers.
            ((HTTP2Session)session).getGenerator().setValidateHpackEncoding(false);
            // Invalid trailer: cannot contain pseudo headers.
            HttpFields.Mutable trailerFields = HttpFields.build();
            trailerFields.put(HttpHeader.C_METHOD, "GET");
            MetaData trailer = new MetaData(HttpVersion.HTTP_2, trailerFields);
            HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), trailer, null, true);
            stream.headers(trailerFrame, Callback.NOOP);
        });

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestTrailersCopiedAsResponseTrailers() throws Exception
    {
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, response::writeTrailers, callback);
            }
        });

        AtomicReference<MetaData.Response> responseRef = new AtomicReference<>();
        AtomicReference<MetaData> trailersRef = new AtomicReference<>();
        CountDownLatch clientLatch = new CountDownLatch(2);
        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(requestFrame, promise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData metaData = frame.getMetaData();
                if (metaData.isResponse())
                {
                    responseRef.set((MetaData.Response)metaData);
                    clientLatch.countDown();
                }
                else if (!metaData.isRequest())
                {
                    trailersRef.set(metaData);
                    clientLatch.countDown();
                }
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        String trailerName = "TrailerName";
        String trailerValue = "TrailerValue";
        HttpFields.Mutable trailers = HttpFields.build().put(trailerName, trailerValue);
        HeadersFrame trailerFrame = new HeadersFrame(stream.getId(), new MetaData(HttpVersion.HTTP_2, trailers), null, true);
        stream.headers(trailerFrame, Callback.NOOP);

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        MetaData.Response responseMetaData = responseRef.get();
        assertEquals(HttpStatus.OK_200, responseMetaData.getStatus());

        MetaData trailerMetaData = trailersRef.get();
        assertEquals(trailerValue, trailerMetaData.getFields().get(trailerName));
    }
}

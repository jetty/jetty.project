//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.parser.RateControl;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2Test extends AbstractTest
{
    @Test
    public void testRequestNoContentResponseNoContent() throws Exception
    {
        start(new EmptyHttpServlet());

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(stream.getId() > 0);

                assertTrue(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());

                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestNoContentResponseEmptyContent() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        stream.data(new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), NOOP);
                    }
                });
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertFalse(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                assertTrue(frame.isEndStream());
                callback.succeeded();
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestNoContentResponseContent() throws Exception
    {
        final byte[] content = "Hello World!".getBytes(StandardCharsets.UTF_8);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getOutputStream().write(content);
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(2);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(stream.getId() > 0);

                assertFalse(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());

                latch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                assertTrue(frame.isEndStream());
                assertEquals(ByteBuffer.wrap(content), frame.getData());

                callback.succeeded();
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestContentResponseContent() throws Exception
    {
        start(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        CountDownLatch latch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("POST", new HttpFields());
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        Promise.Completable<Stream> streamCompletable = new Promise.Completable<>();
        session.newStream(frame, streamCompletable, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        streamCompletable.thenCompose(stream ->
        {
            DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(1024), false);
            Callback.Completable dataCompletable = new Callback.Completable();
            stream.data(dataFrame, dataCompletable);
            return dataCompletable.thenApply(y -> stream);
        }).thenAccept(stream ->
        {
            DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(1024), true);
            stream.data(dataFrame, Callback.NOOP);
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMultipleRequests() throws Exception
    {
        final String downloadBytes = "X-Download";
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                int download = request.getIntHeader(downloadBytes);
                byte[] content = new byte[download];
                new Random().nextBytes(content);
                response.getOutputStream().write(content);
            }
        });

        int requests = 20;
        Session session = newClient(new Session.Listener.Adapter());

        Random random = new Random();
        HttpFields fields = new HttpFields();
        fields.putLongField(downloadBytes, random.nextInt(128 * 1024));
        fields.put("User-Agent", "HTTP2Client/" + Jetty.VERSION);
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(requests);
        for (int i = 0; i < requests; ++i)
        {
            session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
            {
                @Override
                public void onData(Stream stream, DataFrame frame, Callback callback)
                {
                    callback.succeeded();
                    if (frame.isEndStream())
                        latch.countDown();
                }
            });
        }

        assertTrue(latch.await(requests, TimeUnit.SECONDS), server.dump() + System.lineSeparator() + client.dump());
    }

    @Test
    public void testCustomResponseCode() throws Exception
    {
        final int status = 475;
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                response.setStatus(status);
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(status, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHostHeader() throws Exception
    {
        final String host = "fooBar";
        final int port = 1313;
        final String authority = host + ":" + port;
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                assertEquals(host, request.getServerName());
                assertEquals(port, request.getServerPort());
                assertEquals(authority, request.getHeader("Host"));
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        HostPortHttpField hostHeader = new HostPortHttpField(authority);
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP, hostHeader, servletPath, HttpVersion.HTTP_2, new HttpFields());
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSendsGoAwayOnStop() throws Exception
    {
        start(new ServerSessionListener.Adapter());

        CountDownLatch closeLatch = new CountDownLatch(1);
        newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });

        sleep(1000);

        server.stop();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSendsGoAwayOnStop() throws Exception
    {
        CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });

        newClient(new Session.Listener.Adapter());

        sleep(1000);

        client.stop();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxConcurrentStreams() throws Exception
    {
        int maxStreams = 2;
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>(1);
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxStreams);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields(), 0);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }
        });

        CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request1 = newRequest("GET", new HttpFields());
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        CountDownLatch exchangeLatch1 = new CountDownLatch(2);
        session.newStream(new HeadersFrame(request1, null, false), promise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    exchangeLatch1.countDown();
            }
        });
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);

        MetaData.Request request2 = newRequest("GET", new HttpFields());
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        CountDownLatch exchangeLatch2 = new CountDownLatch(2);
        session.newStream(new HeadersFrame(request2, null, false), promise2, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    exchangeLatch2.countDown();
            }
        });
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);

        // The third stream must not be created.
        MetaData.Request request3 = newRequest("GET", new HttpFields());
        CountDownLatch maxStreamsLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(request3, null, false), new Promise.Adapter<Stream>()
        {
            @Override
            public void failed(Throwable x)
            {
                if (x instanceof IllegalStateException)
                    maxStreamsLatch.countDown();
            }
        }, new Stream.Listener.Adapter());

        assertTrue(maxStreamsLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, session.getStreams().size());

        // End the second stream.
        stream2.data(new DataFrame(stream2.getId(), BufferUtil.EMPTY_BUFFER, true), new Callback()
        {
            @Override
            public void succeeded()
            {
                exchangeLatch2.countDown();
            }
        });
        assertTrue(exchangeLatch2.await(5, TimeUnit.SECONDS));
        assertEquals(1, session.getStreams().size());

        // Create a fourth stream.
        MetaData.Request request4 = newRequest("GET", new HttpFields());
        CountDownLatch exchangeLatch4 = new CountDownLatch(2);
        session.newStream(new HeadersFrame(request4, null, true), new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream result)
            {
                exchangeLatch4.countDown();
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    exchangeLatch4.countDown();
            }
        });
        assertTrue(exchangeLatch4.await(5, TimeUnit.SECONDS));
        // The stream is removed from the session just after returning from onHeaders(), so wait a little bit.
        await().atMost(Duration.ofSeconds(1)).until(() -> session.getStreams().size(), is(1));

        // End the first stream.
        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), new Callback()
        {
            @Override
            public void succeeded()
            {
                exchangeLatch1.countDown();
            }
        });
        assertTrue(exchangeLatch2.await(5, TimeUnit.SECONDS));
        assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testInvalidAPIUsageOnClient() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                Callback.Completable completable = new Callback.Completable();
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), completable);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                        {
                            completable.thenRun(() ->
                            {
                                DataFrame endFrame = new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true);
                                stream.data(endFrame, Callback.NOOP);
                            });
                        }
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        Promise.Completable<Stream> completable = new Promise.Completable<>();
        CountDownLatch completeLatch = new CountDownLatch(2);
        session.newStream(frame, completable, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    completeLatch.countDown();
            }
        });
        Stream stream = completable.get(5, TimeUnit.SECONDS);

        long sleep = 1000;
        DataFrame data1 = new DataFrame(stream.getId(), ByteBuffer.allocate(1024), false)
        {
            @Override
            public ByteBuffer getData()
            {
                sleep(2 * sleep);
                return super.getData();
            }
        };
        DataFrame data2 = new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true);

        new Thread(() ->
        {
            // The first data() call is legal, but slow.
            stream.data(data1, new Callback()
            {
                @Override
                public void succeeded()
                {
                    stream.data(data2, NOOP);
                }
            });
        }).start();

        // Wait for the first data() call to happen.
        sleep(sleep);

        // This data call is illegal because it does not
        // wait for the previous callback to complete.
        stream.data(data2, new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                if (x instanceof WritePendingException)
                {
                    // Expected.
                    completeLatch.countDown();
                }
            }
        });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInvalidAPIUsageOnServer() throws Exception
    {
        long sleep = 1000;
        CountDownLatch completeLatch = new CountDownLatch(2);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                DataFrame dataFrame = new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true);
                // The call to headers() is legal, but slow.
                new Thread(() ->
                {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, false)
                    {
                        @Override
                        public MetaData getMetaData()
                        {
                            sleep(2 * sleep);
                            return super.getMetaData();
                        }
                    }, new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            stream.data(dataFrame, NOOP);
                        }
                    });
                }).start();

                // Wait for the headers() call to happen.
                sleep(sleep);

                // This data call is illegal because it does not
                // wait for the previous callback to complete.
                stream.data(dataFrame, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        if (x instanceof WritePendingException)
                        {
                            // Expected.
                            completeLatch.countDown();
                        }
                    }
                });

                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    completeLatch.countDown();
            }
        });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCleanGoAwayDoesNotTriggerFailureNotification() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                HeadersFrame response = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(response, Callback.NOOP);
                // Close cleanly.
                stream.getSession().close(ErrorCode.NO_ERROR.code, null, Callback.NOOP);
                return null;
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
                failureLatch.countDown();
            }
        });
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        session.newStream(request, new Promise.Adapter<>(), new Stream.Listener.Adapter());

        // Make sure onClose() is called.
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertFalse(failureLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testGoAwayRespondedWithGoAway() throws Exception
    {
        ServerSessionListener.Adapter serverListener = new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                HeadersFrame response = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(response, Callback.NOOP);
                stream.getSession().close(ErrorCode.NO_ERROR.code, null, Callback.NOOP);
                return null;
            }
        };
        CountDownLatch goAwayLatch = new CountDownLatch(1);
        RawHTTP2ServerConnectionFactory connectionFactory = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), serverListener)
        {
            @Override
            protected ServerParser newServerParser(Connector connector, ServerParser.Listener listener, RateControl rateControl)
            {
                return super.newServerParser(connector, new ServerParser.Listener.Wrapper(listener)
                {
                    @Override
                    public void onGoAway(GoAwayFrame frame)
                    {
                        super.onGoAway(frame);
                        goAwayLatch.countDown();
                    }
                }, rateControl);
            }
        };
        prepareServer(connectionFactory);
        server.start();

        prepareClient();
        client.start();

        CountDownLatch closeLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(request, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientInvalidHeader() throws Exception
    {
        start(new EmptyHttpServlet());

        // A bad header in the request should fail on the client.
        Session session = newClient(new Session.Listener.Adapter());
        HttpFields requestFields = new HttpFields();
        requestFields.put(":custom", "special");
        MetaData.Request metaData = newRequest("GET", requestFields);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(request, promise, new Stream.Listener.Adapter());
        ExecutionException x = assertThrows(ExecutionException.class, () -> promise.get(5, TimeUnit.SECONDS));
        assertThat(x.getCause(), instanceOf(HpackException.StreamException.class));
    }

    @Test
    public void testServerInvalidHeader() throws Exception
    {
        start(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                response.setHeader(":custom", "special");
            }
        });

        // Good request with bad header in the response.
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(request, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertNotNull(stream);

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerInvalidHeaderFlushed() throws Exception
    {
        CountDownLatch serverFailure = new CountDownLatch(1);
        start(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setHeader(":custom", "special");
                try
                {
                    response.flushBuffer();
                }
                catch (IOException x)
                {
                    assertThat(x.getCause(), instanceOf(HpackException.StreamException.class));
                    serverFailure.countDown();
                    throw x;
                }
            }
        });

        // Good request with bad header in the response.
        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", "/flush", new HttpFields());
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(request, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                // Cannot receive a 500 because we force the flush on the server, so
                // the response is committed even if the server was not able to write it.
                resetLatch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertNotNull(stream);
        assertTrue(serverFailure.await(5, TimeUnit.SECONDS));
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    private static void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException();
        }
    }
}

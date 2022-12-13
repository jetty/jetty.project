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

package org.eclipse.jetty.http2.client.http;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.parser.RateControl;
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTransportOverHTTP2Test extends AbstractTest
{
    @Test
    public void testPropertiesAreForwarded() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);
        httpClient.setConnectTimeout(13);
        httpClient.setIdleTimeout(17);
        httpClient.setUseInputDirectByteBuffers(false);
        httpClient.setUseOutputDirectByteBuffers(false);

        httpClient.start();

        assertTrue(http2Client.isStarted());
        assertSame(httpClient.getExecutor(), http2Client.getExecutor());
        assertSame(httpClient.getScheduler(), http2Client.getScheduler());
        assertSame(httpClient.getByteBufferPool(), http2Client.getByteBufferPool());
        assertEquals(httpClient.getConnectTimeout(), http2Client.getConnectTimeout());
        assertEquals(httpClient.getIdleTimeout(), http2Client.getIdleTimeout());
        assertEquals(httpClient.isUseInputDirectByteBuffers(), http2Client.isUseInputDirectByteBuffers());
        assertEquals(httpClient.isUseOutputDirectByteBuffers(), http2Client.isUseOutputDirectByteBuffers());

        httpClient.stop();

        assertTrue(http2Client.isStopped());
    }

    @Test
    public void testRequestAbortSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .onRequestCommit(request -> request.abort(new Exception("explicitly_aborted_by_test")))
                .send());
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResponseAbortSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), metaData, null, false), new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        ByteBuffer data = ByteBuffer.allocate(1024);
                        stream.data(new DataFrame(stream.getId(), data, false), NOOP);
                    }
                });

                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .onResponseContent((response, buffer) -> response.abort(new Exception("explicitly_aborted_by_test")))
                .send());
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestHasHTTP2Version() throws Exception
    {
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                HttpVersion version = HttpVersion.fromString(request.getProtocol());
                response.setStatus(version == HttpVersion.HTTP_2 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .onRequestBegin(request ->
            {
                if (request.getVersion() != HttpVersion.HTTP_2)
                    request.abort(new Exception("Not an HTTP/2 request"));
            })
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testLastStreamId() throws Exception
    {
        prepareServer(new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 1);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if (HttpMethod.HEAD.is(request.getMethod()))
                {
                    int error = ErrorCode.REFUSED_STREAM_ERROR.code;
                    stream.reset(new ResetFrame(stream.getId(), error), Callback.NOOP);
                    stream.getSession().close(error, null, Callback.NOOP);
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                }
                return null;
            }
        }));
        server.start();

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger lastStream = new AtomicInteger();
        AtomicReference<Stream> streamRef = new AtomicReference<>();
        CountDownLatch streamLatch = new CountDownLatch(1);
        client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client())
        {
            @Override
            protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
            {
                return new HttpConnectionOverHTTP2(destination, session)
                {
                    @Override
                    protected HttpChannelOverHTTP2 newHttpChannel()
                    {
                        return new HttpChannelOverHTTP2(getHttpDestination(), this, getSession())
                        {
                            @Override
                            public void setStream(Stream stream)
                            {
                                super.setStream(stream);
                                if (stream != null)
                                {
                                    streamRef.set(stream);
                                    streamLatch.countDown();
                                }
                            }
                        };
                    }
                };
            }

            @Override
            protected void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
            {
                super.onClose(connection, frame);
                lastStream.set(frame.getLastStreamId());
                latch.countDown();
            }
        });
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.start();

        // Prime the connection to allow client and server prefaces to be exchanged.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .path("/zero")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        org.eclipse.jetty.client.api.Request request = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.HEAD)
            .path("/one");
        request.send(result ->
        {
            if (result.isFailed())
                latch.countDown();
        });

        assertTrue(streamLatch.await(5, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        Stream stream = streamRef.get();
        assertNotNull(stream);
        assertEquals(lastStream.get(), stream.getId());
    }

    @Test
    public void testAbsoluteFormTarget() throws Exception
    {
        String path = "/path";
        String query = "a=b";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                assertEquals(path, request.getRequestURI());
                assertEquals(query, request.getQueryString());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .path("http://localhost:" + connector.getLocalPort() + path + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRequestViaForwardHttpProxy() throws Exception
    {
        String path = "/path";
        String query = "a=b";
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                assertEquals(path, request.getRequestURI());
                assertEquals(query, request.getQueryString());
            }
        });

        int proxyPort = connector.getLocalPort();
        client.getProxyConfiguration().addProxy(new HttpProxy(new Origin.Address("localhost", proxyPort), false, new Origin.Protocol(List.of("h2c"), false)));

        int serverPort = proxyPort + 1; // Any port will do, just not the same as the proxy.
        ContentResponse response = client.newRequest("localhost", serverPort)
            .path(path + "?" + query)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testConnectionIdleTimeoutSendsResetFrame() throws Exception
    {
        long idleTimeout = 1000;

        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });
        client.stop();
        client.setIdleTimeout(idleTimeout);
        client.start();

        assertThrows(TimeoutException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                // Make sure the connection idle times out, not the stream.
                .idleTimeout(2 * idleTimeout, TimeUnit.MILLISECONDS)
                .send());

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestIdleTimeoutSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        assertThrows(TimeoutException.class, () ->
        {
            long idleTimeout = 1000;
            client.newRequest("localhost", connector.getLocalPort())
                .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                .send();
        });

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientStopsServerDoesNotCloseClientCloses() throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            List<Session> sessions = new ArrayList<>();
            HTTP2Client h2Client = new HTTP2Client();
            HttpClient client = new HttpClient(new HttpClientTransportOverHTTP2(h2Client)
            {
                @Override
                protected HttpConnectionOverHTTP2 newHttpConnection(HttpDestination destination, Session session)
                {
                    sessions.add(session);
                    return super.newHttpConnection(destination, session);
                }
            });
            QueuedThreadPool clientExecutor = new QueuedThreadPool();
            clientExecutor.setName("client");
            client.setExecutor(clientExecutor);
            client.start();

            CountDownLatch resultLatch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        resultLatch.countDown();
                });

            ByteBufferPool byteBufferPool = new MappedByteBufferPool();
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            Generator generator = new Generator(byteBufferPool);

            try (Socket socket = server.accept())
            {
                socket.setSoTimeout(1000);
                OutputStream output = socket.getOutputStream();
                InputStream input = socket.getInputStream();

                ServerParser parser = new ServerParser(byteBufferPool, new ServerParser.Listener.Adapter()
                {
                    @Override
                    public void onPreface()
                    {
                        try
                        {
                            // Server's preface.
                            generator.control(lease, new SettingsFrame(new HashMap<>(), false));
                            // Reply to client's SETTINGS.
                            generator.control(lease, new SettingsFrame(new HashMap<>(), true));
                            writeFrames();
                        }
                        catch (HpackException x)
                        {
                            x.printStackTrace();
                        }
                    }

                    @Override
                    public void onHeaders(HeadersFrame request)
                    {
                        try
                        {
                            // Response.
                            MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            HeadersFrame response = new HeadersFrame(request.getStreamId(), metaData, null, true);
                            generator.control(lease, response);
                            writeFrames();
                        }
                        catch (HpackException x)
                        {
                            x.printStackTrace();
                        }
                    }

                    private void writeFrames()
                    {
                        try
                        {
                            // Write the frames.
                            for (ByteBuffer buffer : lease.getByteBuffers())
                            {
                                output.write(BufferUtil.toArray(buffer));
                            }
                            lease.recycle();
                        }
                        catch (Throwable x)
                        {
                            x.printStackTrace();
                        }
                    }
                }, 4096, 8192, RateControl.NO_RATE_CONTROL);
                parser.init(UnaryOperator.identity());

                byte[] bytes = new byte[1024];
                while (true)
                {
                    try
                    {
                        int read = input.read(bytes);
                        assertThat(read, greaterThanOrEqualTo(0));
                        parser.parse(ByteBuffer.wrap(bytes, 0, read));
                    }
                    catch (SocketTimeoutException x)
                    {
                        break;
                    }
                }

                assertTrue(resultLatch.await(5, TimeUnit.SECONDS));

                // The client will send a GO_AWAY, but the server will not close.
                client.stop();

                // Give some time to process the stop/close operations.
                Thread.sleep(1000);

                assertTrue(h2Client.getBeans(Session.class).isEmpty());

                for (Session session : sessions)
                {
                    assertTrue(session.isClosed());
                    assertTrue(((HTTP2Session)session).isDisconnected());
                }
            }
        }
    }

    @Test
    public void test204WithContent() throws Exception
    {
        byte[] bytes = "No Content".getBytes(StandardCharsets.UTF_8);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                int streamId = stream.getId();
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.NO_CONTENT_204, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(streamId, response, null, false);
                Callback.Completable callback = new Callback.Completable();
                stream.headers(responseFrame, callback);
                callback.thenRun(() -> stream.data(new DataFrame(streamId, ByteBuffer.wrap(bytes), true), Callback.NOOP));
                return null;
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
        // No logic on the client to discard content for no-content status codes.
        assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testInvalidResponseHPack() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Disable checks for invalid headers.
                ((HTTP2Session)stream.getSession()).getGenerator().setValidateHpackEncoding(false);
                // Produce an invalid HPACK block by adding a request pseudo-header to the response.
                HttpFields fields = HttpFields.build()
                    .put(":method", "get");
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, fields, 0);
                int streamId = stream.getId();
                HeadersFrame responseFrame = new HeadersFrame(streamId, response, null, false);
                Callback.Completable callback = new Callback.Completable();
                stream.headers(responseFrame, callback);
                byte[] bytes = "hello".getBytes(StandardCharsets.US_ASCII);
                callback.thenRun(() -> stream.data(new DataFrame(streamId, ByteBuffer.wrap(bytes), true), Callback.NOOP));
                return null;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStreamResponseListener() throws Exception
    {
        var bytes = 100_000;
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                int streamId = stream.getId();
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(streamId, response, null, false);
                Callback.Completable callback = new Callback.Completable();
                stream.headers(responseFrame, callback);
                callback.thenRun(() -> stream.data(new DataFrame(streamId, ByteBuffer.wrap(new byte[bytes]), true), Callback.NOOP));
                return null;
            }
        });

        var requestCount = 10_000;
        IntStream.range(0, requestCount).forEach(i ->
        {
            try
            {
                InputStreamResponseListener listener = new InputStreamResponseListener();
                client.newRequest("localhost", connector.getLocalPort()).headers(httpFields -> httpFields.put("X-Request-Id", Integer.toString(i))).send(listener);
                Response response = listener.get(15, TimeUnit.SECONDS);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals(bytes, listener.getInputStream().readAllBytes().length);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    @Disabled
    @Test
    @Tag("external")
    public void testExternalServer() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        clientConnector.setSslContextFactory(sslContextFactory);
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        Executor executor = new QueuedThreadPool();
        clientConnector.setExecutor(executor);
        httpClient.start();

//        ContentResponse response = httpClient.GET("https://http2.akamai.com/");
        ContentResponse response = httpClient.GET("https://webtide.com/");

        assertEquals(HttpStatus.OK_200, response.getStatus());

        httpClient.stop();
    }
}

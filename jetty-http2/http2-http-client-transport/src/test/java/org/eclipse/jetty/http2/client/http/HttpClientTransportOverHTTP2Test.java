//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
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
import org.eclipse.jetty.http2.parser.ServerParser;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class HttpClientTransportOverHTTP2Test extends AbstractTest
{
    @Test
    public void testPropertiesAreForwarded() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), null);
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);
        httpClient.setConnectTimeout(13);
        httpClient.setIdleTimeout(17);

        httpClient.start();

        Assert.assertTrue(http2Client.isStarted());
        Assert.assertSame(httpClient.getExecutor(), http2Client.getExecutor());
        Assert.assertSame(httpClient.getScheduler(), http2Client.getScheduler());
        Assert.assertSame(httpClient.getByteBufferPool(), http2Client.getByteBufferPool());
        Assert.assertEquals(httpClient.getConnectTimeout(), http2Client.getConnectTimeout());
        Assert.assertEquals(httpClient.getIdleTimeout(), http2Client.getIdleTimeout());

        httpClient.stop();

        Assert.assertTrue(http2Client.isStopped());
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onRequestCommit(request -> request.abort(new Exception("explicitly_aborted_by_test")))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
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
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onResponseContent((response, buffer) -> response.abort(new Exception("explicitly_aborted_by_test")))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestHasHTTP2Version() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                HttpVersion version = HttpVersion.fromString(request.getProtocol());
                response.setStatus(version == HttpVersion.HTTP_2 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .onRequestBegin(request ->
                {
                    if (request.getVersion() != HttpVersion.HTTP_2)
                        request.abort(new Exception("Not a HTTP/2 request"));
                })
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
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
                    stream.getSession().close(ErrorCode.REFUSED_STREAM_ERROR.code, null, Callback.NOOP);
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
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
                                streamRef.set(stream);
                                streamLatch.countDown();
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
        }, null);
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.start();

        // Prime the connection to allow client and server prefaces to be exchanged.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .path("/zero")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        org.eclipse.jetty.client.api.Request request = client.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.HEAD)
                .path("/one");
        request.send(result ->
        {
            if (result.isFailed())
                latch.countDown();
        });

        Assert.assertTrue(streamLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Stream stream = streamRef.get();
        Assert.assertNotNull(stream);
        Assert.assertEquals(lastStream.get(), stream.getId());
    }

    @Test
    public void testAbsoluteFormTarget() throws Exception
    {
        String path = "/path";
        String query = "a=b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .path("http://localhost:" + connector.getLocalPort() + path + "?" + query)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testRequestViaForwardHttpProxy() throws Exception
    {
        String path = "/path";
        String query = "a=b";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertEquals(path, request.getRequestURI());
                Assert.assertEquals(query, request.getQueryString());
            }
        });

        int proxyPort = connector.getLocalPort();
        client.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort));

        int serverPort = proxyPort + 1; // Any port will do, just not the same as the proxy.
        ContentResponse response = client.newRequest("localhost", serverPort)
                .path(path + "?" + query)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    // Make sure the connection idle times out, not the stream.
                    .idleTimeout(2 * idleTimeout, TimeUnit.MILLISECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            // Expected.
        }

        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
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

        try
        {
            long idleTimeout = 1000;
            client.newRequest("localhost", connector.getLocalPort())
                    .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            // Expected.
        }

        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
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
            }, null);
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
                    public void onHeaders(HeadersFrame request)
                    {
                        // Server's preface.
                        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
                        // Reply to client's SETTINGS.
                        generator.control(lease, new SettingsFrame(new HashMap<>(), true));
                        // Response.
                        MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                        HeadersFrame response = new HeadersFrame(request.getStreamId(), metaData, null, true);
                        generator.control(lease, response);

                        try
                        {
                            // Write the frames.
                            for (ByteBuffer buffer : lease.getByteBuffers())
                                output.write(BufferUtil.toArray(buffer));
                        }
                        catch (Throwable x)
                        {
                            x.printStackTrace();
                        }
                    }
                }, 4096, 8192);

                byte[] bytes = new byte[1024];
                while (true)
                {
                    try
                    {
                        int read = input.read(bytes);
                        if (read < 0)
                            Assert.fail();
                        parser.parse(ByteBuffer.wrap(bytes, 0, read));
                    }
                    catch (SocketTimeoutException x)
                    {
                        break;
                    }
                }

                Assert.assertTrue(resultLatch.await(5, TimeUnit.SECONDS));

                // The client will send a GO_AWAY, but the server will not close.
                client.stop();

                // Give some time to process the stop/close operations.
                Thread.sleep(1000);

                Assert.assertTrue(h2Client.getBeans(Session.class).isEmpty());

                for (Session session : sessions)
                {
                    Assert.assertTrue(session.isClosed());
                    Assert.assertTrue(((HTTP2Session)session).isDisconnected());
                }
            }
        }
    }

    @Ignore
    @Test
    public void testExternalServer() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        SslContextFactory sslContextFactory = new SslContextFactory();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), sslContextFactory);
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);

        httpClient.start();

//        ContentResponse response = httpClient.GET("https://http2.akamai.com/");
        ContentResponse response = httpClient.GET("https://webtide.com/");

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        httpClient.stop();
    }
}

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

package org.eclipse.jetty.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncMiddleManServletTest
{
    private static final Logger LOG = Log.getLogger(AsyncMiddleManServletTest.class);
    @Rule
    public final TestTracker tracker = new TestTracker();
    private HttpClient client;
    private Server proxy;
    private ServerConnector proxyConnector;
    private Server server;
    private ServerConnector serverConnector;
    private StacklessLogging stackless;

    private void startServer(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy(AsyncMiddleManServlet proxyServlet) throws Exception
    {
        startProxy(proxyServlet, new HashMap<String, String>());
    }

    private void startProxy(AsyncMiddleManServlet proxyServlet, Map<String, String> initParams) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        String value = initParams.get("outputBufferSize");
        if (value != null)
            configuration.setOutputBufferSize(Integer.valueOf(value));
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        ServletContextHandler proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(proxyServlet);
        proxyServletHolder.setInitParameters(initParams);
        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
        
        stackless=new StacklessLogging(proxyServlet._log);
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientPool = new QueuedThreadPool();
        clientPool.setName("client");
        client = new HttpClient();
        client.setExecutor(clientPool);
        client.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyConnector.getLocalPort()));
        client.start();
    }

    @After
    public void dispose() throws Exception
    {
        client.stop();
        proxy.stop();
        server.stop();
        stackless.close();
    }

    @Test
    public void testZeroContentLength() throws Exception
    {
        startServer(new EchoHttpServlet());
        startProxy(new AsyncMiddleManServlet());
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testClientRequestSmallContentKnownLengthGzipped() throws Exception
    {
        // Lengths smaller than the buffer sizes preserve the Content-Length header.
        testClientRequestContentKnownLengthGzipped(1024, false);
    }

    @Test
    public void testClientRequestLargeContentKnownLengthGzipped() throws Exception
    {
        // Lengths bigger than the buffer sizes will force chunked mode.
        testClientRequestContentKnownLengthGzipped(1024 * 1024, true);
    }

    private void testClientRequestContentKnownLengthGzipped(int length, final boolean expectChunked) throws Exception
    {
        byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);

        startServer(new EchoHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String transferEncoding = request.getHeader(HttpHeader.TRANSFER_ENCODING.asString());
                if (expectChunked)
                    Assert.assertNotNull(transferEncoding);
                else
                    Assert.assertNull(transferEncoding);
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                super.service(request, response);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new GZIPContentTransformer(ContentTransformer.IDENTITY);
            }
        });
        startClient();

        byte[] gzipBytes = gzip(bytes);
        ContentProvider gzipContent = new BytesContentProvider(gzipBytes);

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.CONTENT_ENCODING, "gzip")
                .content(gzipContent)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testServerResponseContentKnownLengthGzipped() throws Exception
    {
        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        final byte[] gzipBytes = gzip(bytes);

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                response.getOutputStream().write(gzipBytes);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new GZIPContentTransformer(ContentTransformer.IDENTITY);
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testTransformUpstreamAndDownstreamKnownContentLengthGzipped() throws Exception
    {
        String data = "<a href=\"http://google.com\">Google</a>";
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);

        startServer(new EchoHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                super.service(request, response);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new GZIPContentTransformer(new HrefTransformer.Client());
            }

            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new GZIPContentTransformer(new HrefTransformer.Server());
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.CONTENT_ENCODING, "gzip")
                .content(new BytesContentProvider(gzip(bytes)))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testTransformGzippedHead() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");

                String sample = "<a href=\"http://webtide.com/\">Webtide</a>\n<a href=\"http://google.com\">Google</a>\n";
                byte[] bytes = sample.getBytes(StandardCharsets.UTF_8);

                ServletOutputStream out = response.getOutputStream();
                out.write(gzip(bytes));

                // create a byte buffer larger enough to create 2 (or more) transforms.
                byte[] randomFiller = new byte[64 * 1024];
                /* fill with nonsense
                 * Using random data to ensure compressed buffer size is large
                 * enough to trigger at least 2 transform() events.
                 */
                new Random().nextBytes(randomFiller);

                out.write(gzip(randomFiller));
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new GZIPContentTransformer(new HeadTransformer());
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.CONTENT_ENCODING, "gzip")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());

        String expectedStr = "<a href=\"http://webtide.com/\">Webtide</a>";
        byte[] expected = expectedStr.getBytes(StandardCharsets.UTF_8);
        Assert.assertArrayEquals(expected, response.getContent());
    }

    @Test
    public void testManySequentialTransformations() throws Exception
    {
        for (int i = 0; i < 8; ++i)
            testTransformUpstreamAndDownstreamKnownContentLengthGzipped();
    }

    @Test
    public void testUpstreamTransformationBufferedGzipped() throws Exception
    {
        startServer(new EchoHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                super.service(request, response);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new GZIPContentTransformer(new BufferingContentTransformer());
            }
        });
        startClient();

        DeferredContentProvider content = new DeferredContentProvider();
        Request request = client.newRequest("localhost", serverConnector.getLocalPort());
        FutureResponseListener listener = new FutureResponseListener(request);
        request.header(HttpHeader.CONTENT_ENCODING, "gzip")
                .content(content)
                .send(listener);
        byte[] bytes = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        content.offer(ByteBuffer.wrap(gzip(bytes)));
        sleep(1000);
        content.close();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testDownstreamTransformationBufferedGzipped() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");

                ServletInputStream input = request.getInputStream();
                ServletOutputStream output = response.getOutputStream();
                int read;
                while ((read = input.read()) >= 0)
                {
                    output.write(read);
                    output.flush();
                }
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new GZIPContentTransformer(new BufferingContentTransformer());
            }
        });
        startClient();

        byte[] bytes = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.CONTENT_ENCODING, "gzip")
                .content(new BytesContentProvider(gzip(bytes)))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testDiscardUpstreamAndDownstreamKnownContentLengthGzipped() throws Exception
    {
        final byte[] bytes = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // decode input stream thru gzip
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                IO.copy(new GZIPInputStream(request.getInputStream()), bos);
                // ensure decompressed is 0 length
                Assert.assertEquals(0, bos.toByteArray().length);
                response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                response.getOutputStream().write(gzip(bytes));
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new GZIPContentTransformer(new DiscardContentTransformer());
            }

            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new GZIPContentTransformer(new DiscardContentTransformer());
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .header(HttpHeader.CONTENT_ENCODING, "gzip")
                .content(new BytesContentProvider(gzip(bytes)))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);
    }

    @Test
    public void testUpstreamTransformationThrowsBeforeCommittingProxyRequest() throws Exception
    {
        startServer(new EchoHttpServlet());
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new ContentTransformer()
                {
                    @Override
                    public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
                    {
                        throw new NullPointerException("explicitly_thrown_by_test");
                    }
                };
            }
        });
        startClient();

        byte[] bytes = new byte[1024];
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .content(new BytesContentProvider(bytes))
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(500, response.getStatus());
    }

    @Test
    public void testUpstreamTransformationThrowsAfterCommittingProxyRequest() throws Exception
    {
        try (StacklessLogging scope = new StacklessLogging(HttpChannel.class))
        {
            startServer(new EchoHttpServlet());
            startProxy(new AsyncMiddleManServlet()
            {
                @Override
                protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
                {
                    return new ContentTransformer()
                    {
                        private int count;

                        @Override
                        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
                        {
                            if (++count < 2)
                                output.add(input);
                            else
                                throw new NullPointerException("explicitly_thrown_by_test");
                        }
                    };
                }
            });
            startClient();

            final CountDownLatch latch = new CountDownLatch(1);
            DeferredContentProvider content = new DeferredContentProvider();
            client.newRequest("localhost", serverConnector.getLocalPort())
            .content(content)
            .send(new Response.CompleteListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == 502)
                        latch.countDown();
                }
            });

            content.offer(ByteBuffer.allocate(512));
            sleep(1000);
            content.offer(ByteBuffer.allocate(512));
            content.close();

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testDownstreamTransformationThrowsAtOnContent() throws Exception
    {
        testDownstreamTransformationThrows(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // To trigger the test failure we need that onContent()
                // is called twice, so the second time the test throws.
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[512]);
                output.flush();
                output.write(new byte[512]);
                output.flush();
            }
        });
    }

    @Test
    public void testDownstreamTransformationThrowsAtOnSuccess() throws Exception
    {
        testDownstreamTransformationThrows(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // To trigger the test failure we need that onContent()
                // is called only once, so the the test throws from onSuccess().
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[512]);
                output.flush();
            }
        });
    }

    private void testDownstreamTransformationThrows(HttpServlet serverServlet) throws Exception
    {
        startServer(serverServlet);
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new ContentTransformer()
                {
                    private int count;

                    @Override
                    public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
                    {
                        if (++count < 2)
                            output.add(input);
                        else
                            throw new NullPointerException("explicitly_thrown_by_test");
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(502, response.getStatus());
    }

    @Test
    public void testLargeChunkedBufferedDownstreamTransformation() throws Exception
    {
        testLargeChunkedBufferedDownstreamTransformation(false);
    }

    @Test
    public void testLargeChunkedGzippedBufferedDownstreamTransformation() throws Exception
    {
        testLargeChunkedBufferedDownstreamTransformation(true);
    }

    private void testLargeChunkedBufferedDownstreamTransformation(final boolean gzipped) throws Exception
    {
        // Tests the race between a incomplete write performed from ProxyResponseListener.onSuccess()
        // and ProxyResponseListener.onComplete() being called before the write has completed.

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                OutputStream output = response.getOutputStream();
                if (gzipped)
                {
                    output = new GZIPOutputStream(output);
                    response.setHeader(HttpHeader.CONTENT_ENCODING.asString(), "gzip");
                }

                Random random = new Random();
                byte[] chunk = new byte[1024 * 1024];
                for (int i = 0; i < 16; ++i)
                {
                    random.nextBytes(chunk);
                    output.write(chunk);
                    output.flush();
                }
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                ContentTransformer transformer = new BufferingContentTransformer();
                if (gzipped)
                    transformer = new GZIPContentTransformer(transformer);
                return transformer;
            }
        });
        startClient();

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
                .onResponseContent(new Response.ContentListener()
                {
                    @Override
                    public void onContent(Response response, ByteBuffer content)
                    {
                        // Slow down the reader so that the
                        // write from the proxy gets congested.
                        sleep(1);
                    }
                })
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isSucceeded());
                        Assert.assertEquals(200, result.getResponse().getStatus());
                        latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(15, TimeUnit.SECONDS));
    }

    @Test
    public void testDownstreamTransformationKnownContentLengthDroppingLastChunk() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                byte[] chunk = new byte[1024];
                int contentLength = 2 * chunk.length;
                response.setContentLength(contentLength);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk);
                output.flush();
                sleep(1000);
                output.write(chunk);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new ContentTransformer()
                {
                    @Override
                    public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
                    {
                        if (!finished)
                            output.add(input);
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testClientRequestReadFailsOnFirstRead() throws Exception
    {
        startServer(new EchoHttpServlet());
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected int readClientRequestContent(ServletInputStream input, byte[] buffer) throws IOException
            {
                throw new IOException("explicitly_thrown_by_test");
            }
        });
        startClient();

        final CountDownLatch latch = new CountDownLatch(1);
        DeferredContentProvider content = new DeferredContentProvider();
        client.newRequest("localhost", serverConnector.getLocalPort())
                .content(content)
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        System.err.println(result);
                        if (result.getResponse().getStatus() == 500)
                            latch.countDown();
                    }
                });
        content.offer(ByteBuffer.allocate(512));
        sleep(1000);
        content.offer(ByteBuffer.allocate(512));
        content.close();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientRequestReadFailsOnSecondRead() throws Exception
    {
        try (StacklessLogging scope = new StacklessLogging(HttpChannel.class))
        {
            startServer(new EchoHttpServlet());
            startProxy(new AsyncMiddleManServlet()
            {
                private int count;

                @Override
                protected int readClientRequestContent(ServletInputStream input, byte[] buffer) throws IOException
                {
                    if (++count < 2)
                        return super.readClientRequestContent(input, buffer);
                    else
                        throw new IOException("explicitly_thrown_by_test");
                }
            });
            startClient();

            final CountDownLatch latch = new CountDownLatch(1);
            DeferredContentProvider content = new DeferredContentProvider();
            client.newRequest("localhost", serverConnector.getLocalPort())
            .content(content)
            .send(new Response.CompleteListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.getResponse().getStatus() == 502)
                        latch.countDown();
                }
            });
            content.offer(ByteBuffer.allocate(512));
            sleep(1000);
            content.offer(ByteBuffer.allocate(512));
            content.close();

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testProxyResponseWriteFailsOnFirstWrite() throws Exception
    {
        testProxyResponseWriteFails(1);
    }

    @Test
    public void testProxyResponseWriteFailsOnSecondWrite() throws Exception
    {
        testProxyResponseWriteFails(2);
    }

    private void testProxyResponseWriteFails(final int writeCount) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                ServletOutputStream output = response.getOutputStream();
                output.write(new byte[512]);
                output.flush();
                output.write(new byte[512]);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            private int count;

            @Override
            protected void writeProxyResponseContent(ServletOutputStream output, ByteBuffer content) throws IOException
            {
                if (++count < writeCount)
                    super.writeProxyResponseContent(output, content);
                else
                    throw new IOException("explicitly_thrown_by_test");
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(502, response.getStatus());
    }

    @Test
    public void testAfterContentTransformer() throws Exception
    {
        final String key0 = "id";
        long value0 = 1;
        final String key1 = "channel";
        String value1 = "foo";
        final String json = "{ \"" + key0 + "\":" + value0 + ", \"" + key1 + "\":\"" + value1 + "\" }";
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            }
        });
        final String key2 = "c";
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new AfterContentTransformer()
                {
                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        InputStream input = source.getInputStream();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> obj = (Map<String, Object>)JSON.parse(new InputStreamReader(input, "UTF-8"));
                        // Transform the object.
                        obj.put(key2, obj.remove(key1));
                        try (OutputStream output = sink.getOutputStream())
                        {
                            output.write(JSON.toString(obj).getBytes(StandardCharsets.UTF_8));
                            return true;
                        }
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>)JSON.parse(response.getContentAsString());
        Assert.assertNotNull(obj);
        Assert.assertEquals(2, obj.size());
        Assert.assertEquals(value0, obj.get(key0));
        Assert.assertEquals(value1, obj.get(key2));
    }

    @Test
    public void testAfterContentTransformerMemoryInputStreamReset() throws Exception
    {
        testAfterContentTransformerInputStreamReset(false);
    }

    @Test
    public void testAfterContentTransformerDiskInputStreamReset() throws Exception
    {
        testAfterContentTransformerInputStreamReset(true);
    }

    private void testAfterContentTransformerInputStreamReset(final boolean overflow) throws Exception
    {
        final byte[] data = new byte[]{'c', 'o', 'f', 'f', 'e', 'e'};
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Write the content in two chunks.
                int chunk = data.length / 2;
                ServletOutputStream output = response.getOutputStream();
                output.write(data, 0, chunk);
                sleep(1000);
                output.write(data, chunk, data.length - chunk);
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new AfterContentTransformer()
                {
                    {
                        setMaxInputBufferSize(overflow ? data.length / 2 : data.length * 2);
                    }

                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        // Consume the stream once.
                        InputStream input = source.getInputStream();
                        IO.copy(input, IO.getNullStream());

                        // Reset the stream and re-read it.
                        input.reset();
                        IO.copy(input, sink.getOutputStream());
                        return true;
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testAfterContentTransformerOverflowingToDisk() throws Exception
    {
        // Make sure the temporary directory we use exists and it's empty.
        final Path targetTestsDir = prepareTargetTestsDir();

        final String key0 = "id";
        long value0 = 1;
        final String key1 = "channel";
        String value1 = "foo";
        final String json = "{ \"" + key0 + "\":" + value0 + ", \"" + key1 + "\":\"" + value1 + "\" }";
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            }
        });
        final String inputPrefix = "in_";
        final String outputPrefix = "out_";
        final String key2 = "c";
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                AfterContentTransformer transformer = new AfterContentTransformer()
                {
                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        InputStream input = source.getInputStream();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> obj = (Map<String, Object>)JSON.parse(new InputStreamReader(input, "UTF-8"));
                        // Transform the object.
                        obj.put(key2, obj.remove(key1));
                        try (OutputStream output = sink.getOutputStream())
                        {
                            output.write(JSON.toString(obj).getBytes(StandardCharsets.UTF_8));
                            return true;
                        }
                    }
                };
                transformer.setOverflowDirectory(targetTestsDir);
                int maxBufferSize = json.length() / 4;
                transformer.setMaxInputBufferSize(maxBufferSize);
                transformer.setInputFilePrefix(inputPrefix);
                transformer.setMaxOutputBufferSize(maxBufferSize);
                transformer.setOutputFilePrefix(outputPrefix);
                return transformer;
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>)JSON.parse(response.getContentAsString());
        Assert.assertNotNull(obj);
        Assert.assertEquals(2, obj.size());
        Assert.assertEquals(value0, obj.get(key0));
        Assert.assertEquals(value1, obj.get(key2));
        // Make sure the files do not exist.
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(targetTestsDir, inputPrefix + "*.*"))
        {
            Assert.assertFalse(paths.iterator().hasNext());
        }

        // File deletion is delayed on windows, testing for deletion is not going to work
        if(!OS.IS_WINDOWS)
        {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(targetTestsDir, outputPrefix + "*.*"))
            {
                Assert.assertFalse(paths.iterator().hasNext());
            }
        }
    }

    @Test
    public void testAfterContentTransformerClosingFilesOnClientRequestException() throws Exception
    {
        final Path targetTestsDir = prepareTargetTestsDir();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                IO.copy(request.getInputStream(), IO.getNullStream());
            }
        });
        final CountDownLatch destroyLatch = new CountDownLatch(1);
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new AfterContentTransformer()
                {
                    {
                        setOverflowDirectory(targetTestsDir);
                        setMaxInputBufferSize(0);
                        setMaxOutputBufferSize(0);
                    }

                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        IO.copy(source.getInputStream(), sink.getOutputStream());
                        return true;
                    }

                    @Override
                    public void destroy()
                    {
                        super.destroy();
                        destroyLatch.countDown();
                    }
                };
            }
        });
        long idleTimeout = 1000;
        proxyConnector.setIdleTimeout(idleTimeout);
        startClient();

        // Send only part of the content; the proxy will idle timeout.
        final byte[] data = new byte[]{'c', 'a', 'f', 'e'};
        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .content(new BytesContentProvider(data)
                {
                    @Override
                    public long getLength()
                    {
                        return data.length + 1;
                    }
                })
                .timeout(5 * idleTimeout, TimeUnit.MILLISECONDS)
                .send();

        Assert.assertTrue(destroyLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(HttpStatus.REQUEST_TIMEOUT_408, response.getStatus());
    }

    @Test
    public void testAfterContentTransformerClosingFilesOnServerResponseException() throws Exception
    {
        final Path targetTestsDir = prepareTargetTestsDir();

        final CountDownLatch serviceLatch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                response.setContentLength(2);
                // Send only part of the content.
                OutputStream output = response.getOutputStream();
                output.write('x');
                output.flush();
                serviceLatch.countDown();
            }
        });
        final CountDownLatch destroyLatch = new CountDownLatch(1);
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new AfterContentTransformer()
                {
                    {
                        setOverflowDirectory(targetTestsDir);
                        setMaxInputBufferSize(0);
                        setMaxOutputBufferSize(0);
                    }

                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        IO.copy(source.getInputStream(), sink.getOutputStream());
                        return true;
                    }

                    @Override
                    public void destroy()
                    {
                        super.destroy();
                        destroyLatch.countDown();
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertTrue(serviceLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(destroyLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(HttpStatus.BAD_GATEWAY_502, response.getStatus());
    }

    @Test
    public void testAfterContentTransformerDoNotReadSourceDoNotTransform() throws Exception
    {
        testAfterContentTransformerDoNoTransform(false, false);
    }

    @Test
    public void testAfterContentTransformerReadSourceDoNotTransform() throws Exception
    {
        testAfterContentTransformerDoNoTransform(true, true);
    }

    private void testAfterContentTransformerDoNoTransform(final boolean readSource, final boolean useDisk) throws Exception
    {
        final String key0 = "id";
        long value0 = 1;
        final String key1 = "channel";
        String value1 = "foo";
        final String json = "{ \"" + key0 + "\":" + value0 + ", \"" + key1 + "\":\"" + value1 + "\" }";
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            }
        });
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new AfterContentTransformer()
                {
                    {
                        if (useDisk)
                            setMaxInputBufferSize(0);
                    }

                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        if (readSource)
                        {
                            InputStream input = source.getInputStream();
                            JSON.parse(new InputStreamReader(input, "UTF-8"));
                        }
                        // No transformation.
                        return false;
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>)JSON.parse(response.getContentAsString());
        Assert.assertNotNull(obj);
        Assert.assertEquals(2, obj.size());
        Assert.assertEquals(value0, obj.get(key0));
        Assert.assertEquals(value1, obj.get(key1));
    }

    @Test
    public void testServer401() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setStatus(HttpStatus.UNAUTHORIZED_401);
                response.setHeader(HttpHeader.WWW_AUTHENTICATE.asString(), "Basic realm=\"test\"");
            }
        });
        final AtomicBoolean transformed = new AtomicBoolean();
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse)
            {
                return new AfterContentTransformer()
                {
                    @Override
                    public boolean transform(Source source, Sink sink) throws IOException
                    {
                        transformed.set(true);
                        return false;
                    }
                };
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.UNAUTHORIZED_401, response.getStatus());
        Assert.assertFalse(transformed.get());
    }

    @Test
    public void testProxyRequestHeadersSentWhenDiscardingContent() throws Exception
    {
        startServer(new EchoHttpServlet());
        final CountDownLatch proxyRequestLatch = new CountDownLatch(1);
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new DiscardContentTransformer();
            }

            @Override
            protected void sendProxyRequest(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Request proxyRequest)
            {
                proxyRequestLatch.countDown();
                super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
            }
        });
        startClient();

        DeferredContentProvider content = new DeferredContentProvider();
        Request request = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .content(content);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        // Send one chunk of content, the proxy request must not be sent.
        ByteBuffer chunk1 = ByteBuffer.allocate(1024);
        content.offer(chunk1);
        Assert.assertFalse(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        // Send another chunk of content, the proxy request must not be sent.
        ByteBuffer chunk2 = ByteBuffer.allocate(512);
        content.offer(chunk2);
        Assert.assertFalse(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        // Finish the content, request must be sent.
        content.close();
        Assert.assertTrue(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);
    }

    @Test
    public void testProxyRequestHeadersNotSentUntilContent() throws Exception
    {
        startServer(new EchoHttpServlet());
        final CountDownLatch proxyRequestLatch = new CountDownLatch(1);
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new BufferingContentTransformer();
            }

            @Override
            protected void sendProxyRequest(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Request proxyRequest)
            {
                proxyRequestLatch.countDown();
                super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
            }
        });
        startClient();

        DeferredContentProvider content = new DeferredContentProvider();
        Request request = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .content(content);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        // Send one chunk of content, the proxy request must not be sent.
        ByteBuffer chunk1 = ByteBuffer.allocate(1024);
        content.offer(chunk1);
        Assert.assertFalse(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        // Send another chunk of content, the proxy request must not be sent.
        ByteBuffer chunk2 = ByteBuffer.allocate(512);
        content.offer(chunk2);
        Assert.assertFalse(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        // Finish the content, request must be sent.
        content.close();
        Assert.assertTrue(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(chunk1.capacity() + chunk2.capacity(), response.getContent().length);
    }

    @Test
    public void testProxyRequestHeadersNotSentUntilFirstContent() throws Exception
    {
        startServer(new EchoHttpServlet());
        final CountDownLatch proxyRequestLatch = new CountDownLatch(1);
        startProxy(new AsyncMiddleManServlet()
        {
            @Override
            protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest, Request proxyRequest)
            {
                return new ContentTransformer()
                {
                    private ByteBuffer buffer;

                    @Override
                    public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
                    {
                        // Buffer only the first chunk.
                        if (buffer == null)
                        {
                            buffer = ByteBuffer.allocate(input.remaining());
                            buffer.put(input).flip();
                        }
                        else if (buffer.hasRemaining())
                        {
                            output.add(buffer);
                            output.add(input);
                        }
                        else
                        {
                            output.add(input);
                        }
                    }
                };
            }

            @Override
            protected void sendProxyRequest(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Request proxyRequest)
            {
                proxyRequestLatch.countDown();
                super.sendProxyRequest(clientRequest, proxyResponse, proxyRequest);
            }
        });
        startClient();

        DeferredContentProvider content = new DeferredContentProvider();
        Request request = client.newRequest("localhost", serverConnector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .content(content);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        // Send one chunk of content, the proxy request must not be sent.
        ByteBuffer chunk1 = ByteBuffer.allocate(1024);
        content.offer(chunk1);
        Assert.assertFalse(proxyRequestLatch.await(1, TimeUnit.SECONDS));

        // Send another chunk of content, the proxy request must be sent.
        ByteBuffer chunk2 = ByteBuffer.allocate(512);
        content.offer(chunk2);
        Assert.assertTrue(proxyRequestLatch.await(5, TimeUnit.SECONDS));

        // Finish the content.
        content.close();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(chunk1.capacity() + chunk2.capacity(), response.getContent().length);
    }

    private Path prepareTargetTestsDir() throws IOException
    {
        final Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(targetTestsDir, "*.*"))
        {
            for (Path file : files)
            {
                if (!Files.isDirectory(file))
                    Files.delete(file);
            }
        }
        return targetTestsDir;
    }

    private void sleep(long delay)
    {
        try
        {
            Thread.sleep(delay);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeIOException(x);
        }
    }

    private byte[] gzip(byte[] bytes) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out))
        {
            gzipOut.write(bytes);
        }
        return out.toByteArray();
    }

    private static abstract class HrefTransformer implements AsyncMiddleManServlet.ContentTransformer
    {
        private static final String PREFIX = "http://localhost/q=";
        private final HrefParser parser = new HrefParser();
        private final List<ByteBuffer> matches = new ArrayList<>();
        private boolean matching;

        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
        {
            int position = input.position();
            while (input.hasRemaining())
            {
                boolean match = parser.parse(input);

                // Get the slice of what has been parsed so far.
                int limit = input.limit();
                input.limit(input.position());
                input.position(position);
                ByteBuffer slice = input.slice();
                input.position(input.limit());
                input.limit(limit);
                position = input.position();

                if (matching)
                {
                    if (match)
                    {
                        ByteBuffer copy = ByteBuffer.allocate(slice.remaining());
                        copy.put(slice).flip();
                        matches.add(copy);
                    }
                    else
                    {
                        matching = false;

                        // Transform the matches.
                        Utf8StringBuilder builder = new Utf8StringBuilder();
                        for (ByteBuffer buffer : matches)
                            builder.append(buffer);
                        String transformed = transform(builder.toString());
                        output.add(ByteBuffer.wrap(transformed.getBytes(StandardCharsets.UTF_8)));
                        output.add(slice);
                    }
                }
                else
                {
                    if (match)
                    {
                        matching = true;
                        ByteBuffer copy = ByteBuffer.allocate(slice.remaining());
                        copy.put(slice).flip();
                        matches.add(copy);
                    }
                    else
                    {
                        output.add(slice);
                    }
                }
            }
        }

        protected abstract String transform(String value) throws IOException;

        private static class Client extends HrefTransformer
        {
            @Override
            protected String transform(String value) throws IOException
            {
                String result = PREFIX + URLEncoder.encode(value, "UTF-8");
                LOG.debug("{} -> {}", value, result);
                return result;
            }
        }

        private static class Server extends HrefTransformer
        {
            @Override
            protected String transform(String value) throws IOException
            {
                String result = URLDecoder.decode(value.substring(PREFIX.length()), "UTF-8");
                LOG.debug("{} <- {}", value, result);
                return result;
            }
        }
    }

    private static class HrefParser
    {
        private final byte[] token = {'h', 'r', 'e', 'f', '=', '"'};
        private int state;

        private boolean parse(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                int current = buffer.get() & 0xFF;
                if (state < token.length)
                {
                    if (Character.toLowerCase(current) != token[state])
                    {
                        state = 0;
                        continue;
                    }

                    ++state;
                    if (state == token.length)
                        return false;
                }
                else
                {
                    // Look for the ending quote.
                    if (current == '"')
                    {
                        buffer.position(buffer.position() - 1);
                        state = 0;
                        return true;
                    }
                }
            }
            return state == token.length;
        }
    }

    private static class BufferingContentTransformer implements AsyncMiddleManServlet.ContentTransformer
    {
        private final List<ByteBuffer> buffers = new ArrayList<>();

        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
        {
            if (input.hasRemaining())
            {
                ByteBuffer copy = ByteBuffer.allocate(input.remaining());
                copy.put(input).flip();
                buffers.add(copy);
            }

            if (finished)
            {
                Assert.assertFalse(buffers.isEmpty());
                output.addAll(buffers);
                buffers.clear();
            }
        }
    }

    /**
     * A transformer that discards all but the first line of text.
     */
    private static class HeadTransformer implements AsyncMiddleManServlet.ContentTransformer
    {
        private StringBuilder head = new StringBuilder();

        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
        {
            if (input.hasRemaining() && head != null)
            {
                int lnPos = findLineFeed(input);
                if (lnPos == -1)
                {
                    // no linefeed found, copy it all
                    copyHeadBytes(input,input.limit());
                }
                else
                {
                    // found linefeed
                    copyHeadBytes(input,lnPos);
                    output.addAll(getHeadBytes());
                    // mark head as sent
                    head = null;
                }
            }

            if (finished && head != null)
            {
                output.addAll(getHeadBytes());
            }
        }

        private void copyHeadBytes(ByteBuffer input, int pos)
        {
            ByteBuffer dup = input.duplicate();
            dup.limit(pos);
            String str = BufferUtil.toUTF8String(dup);
            head.append(str);
        }

        private int findLineFeed(ByteBuffer input)
        {
            for (int i = input.position(); i < input.limit(); i++)
            {
                byte b = input.get(i);
                if ((b == (byte)'\n') || (b == (byte)'\r'))
                {
                    return i;
                }
            }
            return -1;
        }

        private List<ByteBuffer> getHeadBytes()
        {
            ByteBuffer buf = BufferUtil.toBuffer(head.toString(),StandardCharsets.UTF_8);
            return Collections.singletonList(buf);
        }
    }

    private static class DiscardContentTransformer implements AsyncMiddleManServlet.ContentTransformer
    {
        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
        {
        }
    }
}

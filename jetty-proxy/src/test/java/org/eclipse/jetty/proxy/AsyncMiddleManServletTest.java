//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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

    private void startProxy(HttpServlet proxyServlet) throws Exception
    {
        startProxy(proxyServlet, new HashMap<String, String>());
    }

    private void startProxy(HttpServlet proxyServlet, Map<String, String> initParams) throws Exception
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
                Assert.assertEquals(-1, request.getInputStream().read());
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
        // Tests the race between a incomplete write performed from ProxyResponseListener.onSuccess()
        // and ProxyResponseListener.onComplete() being called before the write has completed.

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                ServletOutputStream output = response.getOutputStream();
                byte[] chunk = new byte[1024 * 1024];
                for (int i = 0; i < 16; ++i)
                {
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
                return new BufferingContentTransformer();
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

    private static class DiscardContentTransformer implements AsyncMiddleManServlet.ContentTransformer
    {
        @Override
        public void transform(ByteBuffer input, boolean finished, List<ByteBuffer> output) throws IOException
        {
        }
    }
}

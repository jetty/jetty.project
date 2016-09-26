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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.EnumSet;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class HttpClientTest extends AbstractTest
{
    public HttpClientTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testRequestWithoutResponseContent() throws Exception
    {
        final int status = HttpStatus.NO_CONTENT_204;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(status);
            }
        });

        ContentResponse response = client.newRequest(newURI())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(status, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);
    }

    @Test
    public void testRequestWithSmallResponseContent() throws Exception
    {
        testRequestWithResponseContent(1024);
    }

    @Test
    public void testRequestWithLargeResponseContent() throws Exception
    {
        testRequestWithResponseContent(1024 * 1024);
    }

    private void testRequestWithResponseContent(int length) throws Exception
    {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setContentLength(length);
                response.getOutputStream().write(bytes);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI());
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.timeout(10, TimeUnit.SECONDS).send(listener);
        ContentResponse response = listener.get();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testRequestWithSmallResponseContentChunked() throws Exception
    {
        testRequestWithResponseContentChunked(512);
    }

    @Test
    public void testRequestWithLargeResponseContentChunked() throws Exception
    {
        testRequestWithResponseContentChunked(512 * 512);
    }

    private void testRequestWithResponseContentChunked(int length) throws Exception
    {
        final byte[] chunk1 = new byte[length];
        final byte[] chunk2 = new byte[length];
        Random random = new Random();
        random.nextBytes(chunk1);
        random.nextBytes(chunk2);
        byte[] bytes = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, bytes, 0, chunk1.length);
        System.arraycopy(chunk2, 0, bytes, chunk1.length, chunk2.length);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();
                output.write(chunk2);
            }
        });

        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI());
        FutureResponseListener listener = new FutureResponseListener(request, 2 * length);
        request.timeout(10, TimeUnit.SECONDS).send(listener);
        ContentResponse response = listener.get();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(bytes, response.getContent());
    }

    @Test
    public void testUploadZeroLengthWithoutResponseContent() throws Exception
    {
        testUploadWithoutResponseContent(0);
    }

    @Test
    public void testUploadSmallWithoutResponseContent() throws Exception
    {
        testUploadWithoutResponseContent(1024);
    }

    @Test
    public void testUploadLargeWithoutResponseContent() throws Exception
    {
        testUploadWithoutResponseContent(1024 * 1024);
    }

    private void testUploadWithoutResponseContent(int length) throws Exception
    {
        final byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                ServletInputStream input = request.getInputStream();
                for (int i = 0; i < bytes.length; ++i)
                    Assert.assertEquals(bytes[i] & 0xFF, input.read());
                Assert.assertEquals(-1, input.read());
            }
        });

        ContentResponse response = client.newRequest(newURI())
                .method(HttpMethod.POST)
                .content(new BytesContentProvider(bytes))
                .timeout(15, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertEquals(0, response.getContent().length);
    }

    @Test
    public void testClientManyWritesSlowServer() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);

                long sleep = 1024;
                long total = 0;
                ServletInputStream input = request.getInputStream();
                byte[] buffer = new byte[1024];
                while (true)
                {
                    int read = input.read(buffer);
                    if (read < 0)
                        break;
                    total += read;
                    if (total >= sleep)
                    {
                        sleep(250);
                        sleep += 256;
                    }
                }

                response.getOutputStream().print(total);
            }
        });

        int chunks = 256;
        int chunkSize = 16;
        byte[][] bytes = IntStream.range(0, chunks).mapToObj(x -> new byte[chunkSize]).toArray(byte[][]::new);
        BytesContentProvider contentProvider = new BytesContentProvider("application/octet-stream", bytes);
        ContentResponse response = client.newRequest(newURI())
                .method(HttpMethod.POST)
                .content(contentProvider)
                .timeout(15, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertEquals(chunks * chunkSize, Integer.parseInt(response.getContentAsString()));
    }

    @Test
    public void testRequestAfterFailedRequest() throws Exception
    {
        int length = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    response.getOutputStream().write(new byte[length]);
                }
                catch(IOException e)
                {}
            }
        });

        // Make a request with a large enough response buffer.
        org.eclipse.jetty.client.api.Request request = client.newRequest(newURI());
        FutureResponseListener listener = new FutureResponseListener(request, length);
        request.send(listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(response.getStatus(), 200);

        // Make a request with a small response buffer, should fail.
        try
        {
            request = client.newRequest(newURI());
            listener = new FutureResponseListener(request, length / 10);
            request.send(listener);
            listener.get(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertThat(x.getMessage(),Matchers.containsString("Buffering capacity exceeded"));
        }

        // Verify that we can make another request.
        request = client.newRequest(newURI());
        listener = new FutureResponseListener(request, length);
        request.send(listener);
        response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(response.getStatus(), 200);
    }

    @Test(expected = ExecutionException.class)
    public void testClientCannotValidateServerCertificate() throws Exception
    {
        // Only run this test for transports over TLS.
        Assume.assumeTrue(EnumSet.of(Transport.HTTPS, Transport.H2).contains(transport));

        startServer(new EmptyServerHandler());

        // Use a default SslContextFactory, requests should fail because the server certificate is unknown.
        client = newHttpClient(provideClientTransport(transport), new SslContextFactory());
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client.setExecutor(clientThreads);
        client.start();

        client.newRequest(newURI())
                .timeout(5, TimeUnit.SECONDS)
                .send();
    }

    @Test
    public void testOPTIONS() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                Assert.assertTrue(HttpMethod.OPTIONS.is(request.getMethod()));
                Assert.assertEquals("*", target);
                Assert.assertEquals("*", request.getPathInfo());
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(getScheme())
                .method(HttpMethod.OPTIONS)
                .path("*")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testOPTIONSWithRelativeRedirect() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if ("*".equals(target))
                {
                    // Be nasty and send a relative redirect.
                    // Code 303 will change the method to GET.
                    response.setStatus(HttpStatus.SEE_OTHER_303);
                    response.setHeader("Location", "/");
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(getScheme())
                .method(HttpMethod.OPTIONS)
                .path("*")
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testDownloadWithInputStreamResponseListener() throws Exception
    {
        String content = "hello world";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().print(content);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(getScheme())
                .onResponseSuccess(response -> latch.countDown())
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        // Response cannot succeed until we read the content.
        Assert.assertFalse(latch.await(500, TimeUnit.MILLISECONDS));

        InputStream input = listener.getInputStream();
        Assert.assertEquals(content, IO.toString(input));

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testConnectionListener() throws Exception
    {
        start(new EmptyServerHandler());

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        client.addBean(new org.eclipse.jetty.io.Connection.Listener()
        {
            @Override
            public void onOpened(org.eclipse.jetty.io.Connection connection)
            {
                openLatch.countDown();
            }

            @Override
            public void onClosed(org.eclipse.jetty.io.Connection connection)
            {
                closeLatch.countDown();
            }
        });

        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(getScheme())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertTrue(openLatch.await(1, TimeUnit.SECONDS));

        Thread.sleep(2 * idleTimeout);
        Assert.assertTrue(closeLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncResponseContentBackPressure() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Large write to generate multiple DATA frames.
                response.getOutputStream().write(new byte[256 * 1024]);
            }
        });

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(getScheme())
                .onResponseContentAsync((response, content, callback) ->
                {
                    if (counter.incrementAndGet() == 1)
                    {
                        callbackRef.set(callback);
                        latchRef.get().countDown();
                    }
                    else
                    {
                        callback.succeeded();
                    }
                })
                .send(result -> completeLatch.countDown());

        Assert.assertTrue(latchRef.get().await(5, TimeUnit.SECONDS));
        // Wait some time to verify that back pressure is applied correctly.
        Thread.sleep(1000);
        Assert.assertEquals(1, counter.get());
        callbackRef.get().succeeded();

        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResponseWithContentCompleteListenerInvokedOnce() throws Exception
    {
        start(new EmptyServerHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                super.handle(target, baseRequest, request, response);
                response.getWriter().write("Jetty");
            }
        });

        AtomicInteger completes = new AtomicInteger();
        client.newRequest(newURI())
                .send(result -> completes.incrementAndGet());

        sleep(1000);

        Assert.assertEquals(1, completes.get());
    }

    private void sleep(long time) throws IOException
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }
}

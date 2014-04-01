//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

import static java.nio.file.StandardOpenOption.CREATE;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpClientStreamTest extends AbstractHttpClientServerTest
{
    public HttpClientStreamTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testFileUpload() throws Exception
    {
        // Prepare a big file to upload
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        Path upload = Paths.get(targetTestsDir.toString(), "http_client_upload.big");
        try (OutputStream output = Files.newOutputStream(upload, CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
                output.write(kb);
        }

        start(new EmptyServerHandler());

        final AtomicLong requestTime = new AtomicLong();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .file(upload)
                .onRequestSuccess(new Request.SuccessListener()
                {
                    @Override
                    public void onSuccess(Request request)
                    {
                        requestTime.set(System.nanoTime());
                    }
                })
                .timeout(10, TimeUnit.SECONDS)
                .send();
        long responseTime = System.nanoTime();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertTrue(requestTime.get() <= responseTime);

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);
    }

    @Test
    public void testDownload() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        int length = 0;
        while (input.read() == value)
        {
            if (length % 100 == 0)
                Thread.sleep(1);
            ++length;
        }

        Assert.assertEquals(data.length, length);

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
        Assert.assertFalse(result.isFailed());
        Assert.assertSame(response, result.getResponse());
    }

    @Test
    public void testDownloadOfUTF8Content() throws Exception
    {
        final byte[] data = new byte[]{(byte)0xC3, (byte)0xA8}; // UTF-8 representation of &egrave;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte b : data)
        {
            int read = input.read();
            assertTrue(read >= 0);
            Assert.assertEquals(b & 0xFF, read);
        }

        Assert.assertEquals(-1, input.read());

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
        Assert.assertFalse(result.isFailed());
        Assert.assertSame(response, result.getResponse());
    }

    @Test
    public void testDownloadWithFailure() throws Exception
    {
        final byte[] data = new byte[64 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Say we want to send this much...
                response.setContentLength(2 * data.length);
                // ...but write only half...
                response.getOutputStream().write(data);
                // ...then shutdown output
                baseRequest.getHttpChannel().getEndPoint().shutdownOutput();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        int length = 0;
        try
        {
            length = 0;
            while (input.read() == value)
            {
                if (length % 100 == 0)
                    Thread.sleep(1);
                ++length;
            }
            fail();
        }
        catch (IOException expected)
        {
        }

        Assert.assertEquals(data.length, length);

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.isFailed());
    }

    @Test(expected = AsynchronousCloseException.class)
    public void testInputStreamResponseListenerClosedBeforeReading() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        InputStream stream = listener.getInputStream();
        // Close the stream immediately
        stream.close();

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(new BytesContentProvider(new byte[]{0, 1, 2, 3}))
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        stream.read(); // Throws
    }

    @Test
    public void testInputStreamResponseListenerClosedWhileWaiting() throws Exception
    {
        final byte[] chunk1 = new byte[]{0, 1};
        final byte[] chunk2 = new byte[]{2, 3};
        final CountDownLatch closeLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setContentLength(chunk1.length + chunk2.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();
                try
                {
                    closeLatch.await(5, TimeUnit.SECONDS);
                    output.write(chunk2);
                    output.flush();
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        final CountDownLatch waitLatch = new CountDownLatch(1);
        final CountDownLatch waitedLatch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener(1)
        {
            @Override
            protected boolean await()
            {
                waitLatch.countDown();
                boolean result = super.await();
                waitedLatch.countDown();
                return result;
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        InputStream stream = listener.getInputStream();
        // Wait until we block
        Assert.assertTrue(waitLatch.await(5, TimeUnit.SECONDS));
        // Close the stream
        stream.close();
        closeLatch.countDown();

        // Be sure we're not stuck waiting
        Assert.assertTrue(waitedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStreamResponseListenerFailedWhileWaiting() throws Exception
    {
        final byte[] chunk1 = new byte[]{0, 1};
        final byte[] chunk2 = new byte[]{2, 3};
        final CountDownLatch closeLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setContentLength(chunk1.length + chunk2.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();
                try
                {
                    closeLatch.await(5, TimeUnit.SECONDS);
                    output.write(chunk2);
                    output.flush();
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        final CountDownLatch waitLatch = new CountDownLatch(1);
        final CountDownLatch waitedLatch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener(1)
        {
            @Override
            protected boolean await()
            {
                waitLatch.countDown();
                boolean result = super.await();
                waitedLatch.countDown();
                return result;
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        // Wait until we block
        Assert.assertTrue(waitLatch.await(5, TimeUnit.SECONDS));
        // Fail the response
        response.abort(new Exception());
        closeLatch.countDown();

        // Be sure we're not stuck waiting
        Assert.assertTrue(waitedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStreamResponseListenerConsumingBeforeWaiting() throws Exception
    {
        final byte[] data = new byte[]{0, 1};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setContentLength(data.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(data);
                output.flush();
            }
        });

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        InputStreamResponseListener listener = new InputStreamResponseListener(1)
        {
            @Override
            protected boolean await()
            {
                // Consume everything just before waiting
                InputStream stream = getInputStream();
                consume(stream, data);
                return super.await();
            }

            private void consume(InputStream stream, byte[] data)
            {
                try
                {
                    for (byte datum : data)
                        Assert.assertEquals(datum, stream.read());
                }
                catch (IOException x)
                {
                    failure.compareAndSet(null, x);
                }
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, result.getResponse().getStatus());
        Assert.assertNull(failure.get());
    }

    @Test
    public void testInputStreamResponseListenerFailedBeforeResponse() throws Exception
    {
        start(new EmptyServerHandler());
        int port = connector.getLocalPort();
        server.stop();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        // Connect to the wrong port
        client.newRequest("localhost", port)
                .scheme(scheme)
                .send(listener);
        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
    }

    @Test(expected = ExecutionException.class)
    public void testInputStreamContentProviderThrowingWhileReading() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[]{0, 1, 2, 3};
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(new InputStreamContentProvider(new InputStream()
                {
                    private int index = 0;

                    @Override
                    public int read() throws IOException
                    {
                        // Will eventually throw ArrayIndexOutOfBounds
                        return data[index++];
                    }
                }, data.length / 2))
                .timeout(5, TimeUnit.SECONDS)
                .send();
    }

    @Test(expected = AsynchronousCloseException.class)
    public void testDownloadWithCloseBeforeContent() throws Exception
    {
        final byte[] data = new byte[128 * 1024];
        byte value = 3;
        Arrays.fill(data, value);
        final CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.flushBuffer();

                try
                {
                    Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);
        input.close();

        latch.countDown();

        input.read(); // Throws
    }

    @Test(expected = AsynchronousCloseException.class)
    public void testDownloadWithCloseMiddleOfContent() throws Exception
    {
        final byte[] data1 = new byte[1024];
        final byte[] data2 = new byte[1024];
        final CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data1);
                response.flushBuffer();

                try
                {
                    Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data2);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte datum1 : data1)
            Assert.assertEquals(datum1, input.read());

        input.close();

        latch.countDown();

        input.read(); // Throws
    }

    @Test
    public void testDownloadWithCloseEndOfContent() throws Exception
    {
        final byte[] data = new byte[1024];
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
                response.flushBuffer();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte datum : data)
            Assert.assertEquals(datum, input.read());

        // Read EOF
        Assert.assertEquals(-1, input.read());

        input.close();

        // Must not throw
        Assert.assertEquals(-1, input.read());
    }

    @Slow
    @Test
    public void testUploadWithDeferredContentProviderFromInputStream() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        try (DeferredContentProvider content = new DeferredContentProvider())
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scheme)
                    .content(content)
                    .send(new Response.CompleteListener()
                    {
                        @Override
                        public void onComplete(Result result)
                        {
                            if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                                latch.countDown();
                        }
                    });

            // Make sure we provide the content *after* the request has been "sent".
            Thread.sleep(1000);

            try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[1024]))
            {
                byte[] buffer = new byte[200];
                int read;
                while ((read = input.read(buffer)) >= 0)
                    content.offer(ByteBuffer.wrap(buffer, 0, read));
            }
        }
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithDeferredContentProviderRacingWithSend() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] data = new byte[512];
        final DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public void setListener(Listener listener)
            {
                super.setListener(listener);
                // Simulate a concurrent call
                offer(ByteBuffer.wrap(data));
                close();
            }
        };

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded() &&
                                result.getResponse().getStatus() == 200 &&
                                Arrays.equals(data, getContent()))
                            latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithDeferredContentProviderRacingWithIterator() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        final byte[] data = new byte[512];
        final AtomicReference<DeferredContentProvider> contentRef = new AtomicReference<>();
        final DeferredContentProvider content = new DeferredContentProvider()
        {
            @Override
            public Iterator<ByteBuffer> iterator()
            {
                return new Iterator<ByteBuffer>()
                {
                    // Data for the deferred content iterator:
                    // [0] => deferred
                    // [1] => deferred
                    // [2] => data
                    private final byte[][] iteratorData = new byte[3][];
                    private final AtomicInteger index = new AtomicInteger();

                    {
                        iteratorData[0] = null;
                        iteratorData[1] = null;
                        iteratorData[2] = data;
                    }

                    @Override
                    public boolean hasNext()
                    {
                        return index.get() < iteratorData.length;
                    }

                    @Override
                    public ByteBuffer next()
                    {
                        byte[] chunk = iteratorData[index.getAndIncrement()];
                        ByteBuffer result = chunk == null ? null : ByteBuffer.wrap(chunk);
                        if (index.get() == 2)
                        {
                            contentRef.get().offer(result == null ? BufferUtil.EMPTY_BUFFER : result);
                            contentRef.get().close();
                        }
                        return result;
                    }

                    @Override
                    public void remove()
                    {
                    }
                };
            }
        };
        contentRef.set(content);

        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded() &&
                                result.getResponse().getStatus() == 200 &&
                                Arrays.equals(data, getContent()))
                            latch.countDown();
                    }
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithOutputStream() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[512];
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        if (result.isSucceeded() &&
                                result.getResponse().getStatus() == 200 &&
                                Arrays.equals(data, getContent()))
                            latch.countDown();
                    }
                });

        // Make sure we provide the content *after* the request has been "sent".
        Thread.sleep(1000);

        try (OutputStream output = content.getOutputStream())
        {
            output.write(data);
        }

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBigUploadWithOutputFromInputStream() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[16 * 1024 * 1024];
        new Random().nextBytes(data);
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(content)
                .send(new BufferingResponseListener(data.length)
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isSucceeded());
                        Assert.assertEquals(200, result.getResponse().getStatus());
                        Assert.assertArrayEquals(data, getContent());
                        latch.countDown();
                    }
                });

        // Make sure we provide the content *after* the request has been "sent".
        Thread.sleep(1000);

        try (InputStream input = new ByteArrayInputStream(data); OutputStream output = content.getOutputStream())
        {
            byte[] buffer = new byte[1024];
            while (true)
            {
                int read = input.read(buffer);
                if (read < 0)
                    break;
                output.write(buffer, 0, read);
            }
        }

        Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithWriteFailureClosesStream() throws Exception
    {
        start(new EmptyServerHandler());

        final AtomicInteger bytes = new AtomicInteger();
        final CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                int result = bytes.incrementAndGet();
                switch (result)
                {
                    case 1:
                    {
                        break;
                    }
                    case 2:
                    {
                        try
                        {
                            connector.stop();
                        }
                        catch (Exception x)
                        {
                            throw new IOException(x);
                        }
                        break;
                    }
                    default:
                    {
                        result = -1;
                        break;
                    }
                }
                return result;
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };
        InputStreamContentProvider provider = new InputStreamContentProvider(stream, 1);

        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .content(provider)
                .send(new Response.CompleteListener()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        Assert.assertTrue(result.isFailed());
                        completeLatch.countDown();
                    }
                });

        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}

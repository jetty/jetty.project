//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Slow;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientStreamTest extends AbstractTest
{
    public HttpClientStreamTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testFileUpload() throws Exception
    {
        // Prepare a big file to upload
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        Path upload = Paths.get(targetTestsDir.toString(), "http_client_upload.big");
        try (OutputStream output = Files.newOutputStream(upload, StandardOpenOption.CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
                output.write(kb);
        }

        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setContentLength(0);
                response.flushBuffer();

                InputStream in = request.getInputStream();
                byte[] buffer = new byte[1024];
                while (true)
                {
                    int read = in.read(buffer);
                    if (read < 0)
                        break;
                }
            }
        });

        final AtomicLong requestTime = new AtomicLong();
        ContentResponse response = client.newRequest(newURI())
                .scheme(getScheme())
                .file(upload)
                .onRequestSuccess(request -> requestTime.set(System.nanoTime()))
                .timeout(30, TimeUnit.SECONDS)
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
        client.newRequest(newURI())
                .scheme(getScheme())
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        Assert.assertNotNull(input);

        for (byte b : data)
        {
            int read = input.read();
            Assert.assertTrue(read >= 0);
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
            Assert.fail();
        }
        catch (IOException x)
        {
            // Expected.
        }

        Assert.assertThat(length, Matchers.lessThanOrEqualTo(data.length));

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
        // Close the stream immediately.
        stream.close();

        client.newRequest(newURI())
                .scheme(getScheme())
                .content(new BytesContentProvider(new byte[]{0, 1, 2, 3}))
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());

        stream.read(); // Throws
    }

    @Test(expected = AsynchronousCloseException.class)
    public void testInputStreamResponseListenerClosedBeforeContent() throws Exception
    {
        AtomicReference<AsyncContext> contextRef = new AtomicReference<>();
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                contextRef.set(request.startAsync());
                response.flushBuffer();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                super.onContent(response, content, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        latch.countDown();
                        callback.failed(x);
                    }
                });
            }
        };
        client.newRequest(newURI())
                .scheme(getScheme())
                .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        InputStream input = listener.getInputStream();
        input.close();

        AsyncContext asyncContext = contextRef.get();
        asyncContext.getResponse().getOutputStream().write(new byte[1024]);
        asyncContext.complete();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        input.read(); // Throws
    }

    @Test
    public void testInputStreamResponseListenerClosedWhileWaiting() throws Exception
    {
        byte[] chunk1 = new byte[]{0, 1};
        byte[] chunk2 = new byte[]{2, 3};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setContentLength(chunk1.length + chunk2.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();
                output.write(chunk2);
            }
        });

        CountDownLatch failedLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                super.onContent(response, content, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        failedLatch.countDown();
                        callback.failed(x);
                    }
                });
                contentLatch.countDown();
            }
        };
        client.newRequest(newURI())
                .scheme(getScheme())
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait until we get some content.
        Assert.assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Close the stream.
        InputStream stream = listener.getInputStream();
        stream.close();

        // Make sure that the callback has been invoked.
        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStreamResponseListenerFailedWhileWaiting() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                byte[] data = new byte[1024];
                response.setContentLength(data.length);
                ServletOutputStream output = response.getOutputStream();
                output.write(data);
            }
        });

        CountDownLatch failedLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        InputStreamResponseListener listener = new InputStreamResponseListener()
        {
            @Override
            public void onContent(Response response, ByteBuffer content, Callback callback)
            {
                super.onContent(response, content, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        failedLatch.countDown();
                        callback.failed(x);
                    }
                });
                contentLatch.countDown();
            }
        };
        client.newRequest(newURI())
                .scheme(getScheme())
                .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait until we get some content.
        Assert.assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Abort the response.
        response.abort(new Exception());

        // Make sure that the callback has been invoked.
        Assert.assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStreamResponseListenerFailedBeforeResponse() throws Exception
    {
        start(new EmptyServerHandler());
        String uri = newURI();
        server.stop();
                
        InputStreamResponseListener listener = new InputStreamResponseListener();
        // Connect to the wrong port
        client.newRequest(uri)
                .scheme(getScheme())
                .send(listener);
        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(result);
    }

    @Test(expected = ExecutionException.class)
    public void testInputStreamContentProviderThrowingWhileReading() throws Exception
    {
        start(new AbstractHandler.ErrorDispatchHandler()
        {
            @Override
            public void doNonErrorHandle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[]{0, 1, 2, 3};
        client.newRequest(newURI())
                .scheme(getScheme())
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
            client.newRequest(newURI())
                    .scheme(getScheme())
                    .content(content)
                    .send(result ->
                    {
                        if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                            latch.countDown();
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
    public void testUploadWithDeferredContentAvailableCallbacksNotifiedOnce() throws Exception
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
        final AtomicInteger succeeds = new AtomicInteger();
        try (DeferredContentProvider content = new DeferredContentProvider())
        {
            // Make the content immediately available.
            content.offer(ByteBuffer.allocate(1024), new Callback()
            {
                @Override
                public void succeeded()
                {
                    succeeds.incrementAndGet();
                }
            });

            client.newRequest(newURI())
                    .scheme(getScheme())
                    .content(content)
                    .send(result ->
                    {
                        if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                            latch.countDown();
                    });
        }
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(1, succeeds.get());
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

        client.newRequest(newURI())
                .scheme(getScheme())
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
                        if (index.get() < iteratorData.length)
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

        client.newRequest(newURI())
                .scheme(getScheme())
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
    public void testBigUploadWithOutputStreamFromInputStream() throws Exception
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
        client.newRequest(newURI())
                .scheme(getScheme())
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
    public void testUploadWithOutputStreamFailureToConnect() throws Exception
    {
        start(new EmptyServerHandler());

        final byte[] data = new byte[512];
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        client.newRequest("http://0.0.0.1"
                              + ((connector instanceof ServerConnector)?":"+ServerConnector.class.cast(connector).getLocalPort():""))
                .scheme(getScheme())
                .content(content)
                .send(result ->
                {
                    if (result.isFailed())
                        latch.countDown();
                });

        try (OutputStream output = content.getOutputStream())
        {
            output.write(data);
            Assert.fail();
        }
        catch (IOException x)
        {
            // Expected
        }

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithDeferredContentProviderFailsMultipleOffers() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch failLatch = new CountDownLatch(2);
        final Callback callback = new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                failLatch.countDown();
            }
        };

        final CountDownLatch completeLatch = new CountDownLatch(1);
        final DeferredContentProvider content = new DeferredContentProvider();
        client.newRequest(newURI())
                .scheme(getScheme())
                .content(content)
                .onRequestBegin(request ->
                {
                    content.offer(ByteBuffer.wrap(new byte[256]), callback);
                    content.offer(ByteBuffer.wrap(new byte[256]), callback);
                    request.abort(new Exception("explicitly_thrown_by_test"));
                })
                .send(result ->
                {
                    if (result.isFailed())
                        completeLatch.countDown();
                });
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(failLatch.await(5, TimeUnit.SECONDS));

        // Make sure that adding more content results in the callback to be failed.
        final CountDownLatch latch = new CountDownLatch(1);
        content.offer(ByteBuffer.wrap(new byte[128]), new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                latch.countDown();
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithConnectFailureClosesStream() throws Exception
    {
        start(new EmptyServerHandler());

        final CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8))
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };
        InputStreamContentProvider content = new InputStreamContentProvider(stream);

        final CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("http://0.0.0.1"
                              + ((connector instanceof ServerConnector)?":"+ServerConnector.class.cast(connector).getLocalPort():""))
                .scheme(getScheme())
                .content(content)
                .send(result ->
                {
                    Assert.assertTrue(result.isFailed());
                    completeLatch.countDown();
                });

        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testUploadWithConcurrentServerCloseClosesStream() throws Exception
    {
        final CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                serverLatch.countDown();
            }
        });

        final AtomicBoolean commit = new AtomicBoolean();
        final CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                // This method will be called few times before
                // the request is committed.
                // We wait for the request to commit, and we
                // wait for the request to reach the server,
                // to be sure that the server endPoint has
                // been created, before stopping the connector.

                if (commit.get())
                {
                    try
                    {
                        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
                        connector.stop();
                        return 0;
                    }
                    catch (Throwable x)
                    {
                        throw new IOException(x);
                    }
                }
                else
                {
                    return connector.isStopped() ? -1 : 0;
                }
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
        client.newRequest(newURI())
                .scheme(getScheme())
                .content(provider)
                .onRequestCommit(request -> commit.set(true))
                .send(result ->
                {
                    Assert.assertTrue(result.isFailed());
                    completeLatch.countDown();
                });

        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInputStreamResponseListenerBufferedRead() throws Exception
    {
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                asyncContextRef.set(request.startAsync());
                latch.countDown();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI())
                .scheme(getScheme())
                .timeout(5, TimeUnit.SECONDS)
                .send(listener);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        AsyncContext asyncContext = asyncContextRef.get();
        Assert.assertNotNull(asyncContext);

        Random random = new Random();

        byte[] chunk = new byte[64];
        random.nextBytes(chunk);
        ServletOutputStream output = asyncContext.getResponse().getOutputStream();
        output.write(chunk);
        output.flush();

        // Use a buffer larger than the data
        // written to test that the read returns.
        byte[] buffer = new byte[2 * chunk.length];
        InputStream stream = listener.getInputStream();
        int totalRead = 0;
        while (totalRead < chunk.length)
        {
            int read = stream.read(buffer);
            Assert.assertTrue(read > 0);
            totalRead += read;
        }

        asyncContext.complete();

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testInputStreamResponseListenerWithRedirect() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (target.startsWith("/303"))
                    response.sendRedirect("/200");
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI())
                .scheme(getScheme())
                .path("/303")
                .followRedirects(true)
                .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Result result = listener.await(5, TimeUnit.SECONDS);
        Assert.assertTrue(result.isSucceeded());
    }
}

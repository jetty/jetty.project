//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
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
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientStreamTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testFileUpload(Transport transport) throws Exception
    {
        init(transport);
        // Prepare a big file to upload
        Path targetTestsDir = MavenTestingUtils.getTargetTestingDir().toPath();
        Files.createDirectories(targetTestsDir);
        Path upload = Paths.get(targetTestsDir.toString(), "http_client_upload.big");
        try (OutputStream output = Files.newOutputStream(upload, StandardOpenOption.CREATE))
        {
            byte[] kb = new byte[1024];
            for (int i = 0; i < 10 * 1024; ++i)
            {
                output.write(kb);
            }
        }

        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .file(upload)
            .onRequestSuccess(request -> requestTime.set(System.nanoTime()))
            .timeout(30, TimeUnit.SECONDS)
            .send();
        long responseTime = System.nanoTime();

        assertEquals(200, response.getStatus());
        assertTrue(requestTime.get() <= responseTime);

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownload(Transport transport) throws Exception
    {
        init(transport);
        final byte[] data = new byte[128 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        int length = 0;
        while (input.read() == value)
        {
            if (length % 100 == 0)
                Thread.sleep(1);
            ++length;
        }

        assertEquals(data.length, length);

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.isFailed());
        assertSame(response, result.getResponse());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadOfUTF8Content(Transport transport) throws Exception
    {
        init(transport);
        final byte[] data = new byte[]{(byte)0xC3, (byte)0xA8}; // UTF-8 representation of &egrave;
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        for (byte b : data)
        {
            int read = input.read();
            assertTrue(read >= 0);
            assertEquals(b & 0xFF, read);
        }

        assertEquals(-1, input.read());

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertFalse(result.isFailed());
        assertSame(response, result.getResponse());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithFailure(Transport transport) throws Exception
    {
        init(transport);
        final byte[] data = new byte[64 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        AtomicInteger length = new AtomicInteger();

        assertThrows(IOException.class, () ->
        {
            while (input.read() == value)
            {
                if (length.incrementAndGet() % 100 == 0)
                    Thread.sleep(1);
            }
        });

        assertThat(length.get(), lessThanOrEqualTo(data.length));

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.isFailed());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerClosedBeforeReading(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        InputStream stream = listener.getInputStream();
        // Close the stream immediately.
        stream.close();

        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .content(new BytesContentProvider(new byte[]{0, 1, 2, 3}))
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        assertThrows(AsynchronousCloseException.class, stream::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerClosedBeforeContent(Transport transport) throws Exception
    {
        init(transport);
        AtomicReference<AsyncContext> contextRef = new AtomicReference<>();
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        InputStream input = listener.getInputStream();
        input.close();

        AsyncContext asyncContext = contextRef.get();
        asyncContext.getResponse().getOutputStream().write(new byte[1024]);
        asyncContext.complete();

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerClosedWhileWaiting(Transport transport) throws Exception
    {
        init(transport);
        byte[] chunk1 = new byte[]{0, 1};
        byte[] chunk2 = new byte[]{2, 3};
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait until we get some content.
        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Close the stream.
        InputStream stream = listener.getInputStream();
        stream.close();

        // Make sure that the callback has been invoked.
        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerFailedWhileWaiting(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait until we get some content.
        assertTrue(contentLatch.await(5, TimeUnit.SECONDS));

        // Abort the response.
        response.abort(new Exception());

        // Make sure that the callback has been invoked.
        assertTrue(failedLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerFailedBeforeResponse(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());
        String uri = scenario.newURI();
        scenario.server.stop();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        // Connect to the wrong port
        scenario.client.newRequest(uri)
            .scheme(scenario.getScheme())
            .send(listener);
        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamContentProviderThrowingWhileReading(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[]{0, 1, 2, 3};
        ExecutionException e = assertThrows(ExecutionException.class, () ->
            scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .content(new InputStreamContentProvider(new InputStream()
                {
                    private int index = 0;

                    @Override
                    public int read()
                    {
                        // Will eventually throw ArrayIndexOutOfBounds
                        return data[index++];
                    }
                }, data.length / 2))
                .timeout(5, TimeUnit.SECONDS)
                .send());
        assertThat(e.getCause(), instanceOf(NoSuchElementException.class));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithCloseBeforeContent(Transport transport) throws Exception
    {
        init(transport);
        final byte[] data = new byte[128 * 1024];
        byte value = 3;
        Arrays.fill(data, value);
        final CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.flushBuffer();

                try
                {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);
        input.close();

        latch.countDown();

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithCloseMiddleOfContent(Transport transport) throws Exception
    {
        init(transport);
        final byte[] data1 = new byte[1024];
        final byte[] data2 = new byte[1024];
        final CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data1);
                response.flushBuffer();

                try
                {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.getOutputStream().write(data2);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        for (byte datum1 : data1)
        {
            assertEquals(datum1, input.read());
        }

        input.close();

        latch.countDown();

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testDownloadWithCloseEndOfContent(Transport transport) throws Exception
    {
        init(transport);
        final byte[] data = new byte[1024];
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().write(data);
                response.flushBuffer();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals(200, response.getStatus());

        InputStream input = listener.getInputStream();
        assertNotNull(input);

        for (byte datum : data)
        {
            assertEquals(datum, input.read());
        }

        // Read EOF
        assertEquals(-1, input.read());

        input.close();

        // Must not throw
        assertEquals(-1, input.read());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    @DisabledIfSystemProperty(named = "env", matches = "ci") // TODO: SLOW, needs review
    public void testUploadWithDeferredContentProviderFromInputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        try (DeferredContentProvider content = new DeferredContentProvider())
        {
            scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
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
                {
                    content.offer(ByteBuffer.wrap(buffer, 0, read));
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentAvailableCallbacksNotifiedOnce(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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

            scenario.client.newRequest(scenario.newURI())
                .scheme(scenario.getScheme())
                .content(content)
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                        latch.countDown();
                });
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, succeeds.get());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentProviderRacingWithSend(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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

        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentProviderRacingWithIterator(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
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

        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithOutputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[512];
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testBigUploadWithOutputStreamFromInputStream(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        final byte[] data = new byte[16 * 1024 * 1024];
        new Random().nextBytes(data);
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .content(content)
            .send(new BufferingResponseListener(data.length)
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertEquals(200, result.getResponse().getStatus());
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        // Make sure we provide the content *after* the request has been "sent".
        Thread.sleep(1000);

        try (InputStream input = new ByteArrayInputStream(data);
             OutputStream output = content.getOutputStream())
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

        assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithOutputStreamFailureToConnect(Transport transport) throws Exception
    {
        init(transport);

        long connectTimeout = 1000;
        scenario.start(new EmptyServerHandler(), httpClient -> httpClient.setConnectTimeout(connectTimeout));

        final byte[] data = new byte[512];
        final CountDownLatch latch = new CountDownLatch(1);
        OutputStreamContentProvider content = new OutputStreamContentProvider();
        String uri = "http://0.0.0.1";
        if (scenario.getNetworkConnectorLocalPort().isPresent())
            uri += ":" + scenario.getNetworkConnectorLocalPort().get();
        scenario.client.newRequest(uri)
            .scheme(scenario.getScheme())
            .content(content)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertThrows(IOException.class, () ->
        {
            try (OutputStream output = content.getOutputStream())
            {
                output.write(data);
            }
        });

        assertTrue(latch.await(2 * connectTimeout, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithDeferredContentProviderFailsMultipleOffers(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());

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
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
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
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failLatch.await(5, TimeUnit.SECONDS));

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
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithConnectFailureClosesStream(Transport transport) throws Exception
    {
        init(transport);

        long connectTimeout = 1000;
        scenario.start(new EmptyServerHandler(), httpClient -> httpClient.setConnectTimeout(connectTimeout));

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
        String uri = "http://0.0.0.1";
        if (scenario.getNetworkConnectorLocalPort().isPresent())
            uri += ":" + scenario.getNetworkConnectorLocalPort().get();
        scenario.client.newRequest(uri)
            .scheme(scenario.getScheme())
            .content(content)
            .send(result ->
            {
                assertTrue(result.isFailed());
                completeLatch.countDown();
            });

        assertTrue(completeLatch.await(2 * connectTimeout, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testUploadWithConcurrentServerCloseClosesStream(Transport transport) throws Exception
    {
        init(transport);
        final CountDownLatch serverLatch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
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
                        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
                        scenario.connector.stop();
                        return 0;
                    }
                    catch (Throwable x)
                    {
                        throw new IOException(x);
                    }
                }
                else
                {
                    return scenario.connector.isStopped() ? -1 : 0;
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
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .content(provider)
            .onRequestCommit(request -> commit.set(true))
            .send(result ->
            {
                assertTrue(result.isFailed());
                completeLatch.countDown();
            });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerBufferedRead(Transport transport) throws Exception
    {
        init(transport);
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                asyncContextRef.set(request.startAsync());
                latch.countDown();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        AsyncContext asyncContext = asyncContextRef.get();
        assertNotNull(asyncContext);

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
            assertTrue(read > 0);
            totalRead += read;
        }

        asyncContext.complete();

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testInputStreamResponseListenerWithRedirect(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                if (target.startsWith("/303"))
                    response.sendRedirect("/200");
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        scenario.client.newRequest(scenario.newURI())
            .scheme(scenario.getScheme())
            .path("/303")
            .followRedirects(true)
            .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertTrue(result.isSucceeded());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testClientDefersContentServerIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        CountDownLatch dataLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        scenario.start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        dataLatch.countDown();
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        dataLatch.countDown();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        errorLatch.countDown();
                        response.setStatus(HttpStatus.REQUEST_TIMEOUT_408);
                        asyncContext.complete();
                    }
                });
            }
        });
        long idleTimeout = 1000;
        scenario.setServerIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = "[{\"key\":\"value\"}]".getBytes(StandardCharsets.UTF_8);
        OutputStreamContentProvider content = new OutputStreamContentProvider()
        {
            @Override
            public long getLength()
            {
                return bytes.length;
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .method(HttpMethod.POST)
            .path(scenario.servletPath)
            .content(content, "application/json;charset=UTF-8")
            .onResponseSuccess(response ->
            {
                assertEquals(HttpStatus.REQUEST_TIMEOUT_408, response.getStatus());
                latch.countDown();
            })
            .send(null);

        // Wait for the server to idle timeout.
        Thread.sleep(2 * idleTimeout);

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));

        // Do not send the content to the server.

        assertFalse(dataLatch.await(1, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}

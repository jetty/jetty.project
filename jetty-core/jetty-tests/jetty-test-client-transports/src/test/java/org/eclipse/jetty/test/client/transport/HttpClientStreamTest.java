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

package org.eclipse.jetty.test.client.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.client.util.InputStreamRequestContent;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

public class HttpClientStreamTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testFileUpload(Transport transport) throws Exception
    {
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

        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 0);
                Content.Sink.write(response, false, null);

                Content.Source.consumeAll(request);

                callback.succeeded();
            }
        });

        AtomicLong requestTime = new AtomicLong();
        ContentResponse response = client.newRequest(newURI(transport))
            .file(upload)
            .onRequestSuccess(request -> requestTime.set(NanoTime.now()))
            .timeout(2, TimeUnit.MINUTES)
            .send();
        long responseTime = NanoTime.now();

        assertEquals(200, response.getStatus());
        assertTrue(NanoTime.isBeforeOrSame(requestTime.get(), responseTime));

        // Give some time to the server to consume the request content
        // This is just to avoid exception traces in the test output
        Thread.sleep(1000);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDownload(Transport transport) throws Exception
    {
        byte[] data = new byte[128 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testDownloadOfUTF8Content(Transport transport) throws Exception
    {
        byte[] data = new byte[]{(byte)0xC3, (byte)0xA8}; // UTF-8 representation of &egrave;
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testDownloadWithFailure(Transport transport) throws Exception
    {
        byte[] data = new byte[64 * 1024];
        byte value = 1;
        Arrays.fill(data, value);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                // Say we want to send this much...
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 2 * data.length);
                // ...but write only half...
                Content.Sink.write(response, false, ByteBuffer.wrap(data));
                // ...then shutdown output
                request.getConnectionMetaData().getConnection().getEndPoint().shutdownOutput();
                callback.succeeded();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testInputStreamResponseListenerClosedBeforeReading(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        InputStream stream = listener.getInputStream();
        // Close the stream immediately.
        stream.close();

        client.newRequest(newURI(transport))
            .body(new BytesRequestContent(new byte[]{0, 1, 2, 3}))
            .send(listener);
        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());

        assertThrows(AsynchronousCloseException.class, stream::read);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testInputStreamResponseListenerClosedBeforeContent(Transport transport) throws Exception
    {
        AtomicReference<HandlerContext> contextRef = new AtomicReference<>();
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                contextRef.set(new HandlerContext(request, response, callback));
                Content.Sink.write(response, false, null);
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
        client.newRequest(newURI(transport))
            .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        InputStream input = listener.getInputStream();
        input.close();

        HandlerContext handlerContext = contextRef.get();
        handlerContext.response().write(true, ByteBuffer.allocate(1024), handlerContext.callback());

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertThrows(AsynchronousCloseException.class, input::read);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testInputStreamResponseListenerClosedWhileWaiting(Transport transport) throws Exception
    {
        byte[] chunk1 = new byte[]{0, 1};
        byte[] chunk2 = new byte[]{2, 3};
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, chunk1.length + chunk2.length);
                Content.Sink.write(response, false, ByteBuffer.wrap(chunk1));
                response.write(true, ByteBuffer.wrap(chunk2), callback);
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
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testInputStreamResponseListenerFailedWhileWaiting(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                byte[] data = new byte[1024];
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length);
                response.write(true, ByteBuffer.wrap(data), callback);
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
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testInputStreamResponseListenerFailedBeforeResponse(Transport transport) throws Exception
    {
        // Failure to connect is based on TCP connection refused
        // (as the server is stopped), which does not work for UDP.
        Assumptions.assumeTrue(transport != Transport.H3);

        start(transport, new EmptyServerHandler());
        URI uri = newURI(transport);
        server.stop();

        InputStreamResponseListener listener = new InputStreamResponseListener();
        // Connect to the wrong port
        client.newRequest(uri)
            .send(listener);
        Result result = listener.await(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testInputStreamContentProviderThrowingWhileReading(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        byte[] data = new byte[]{0, 1, 2, 3};
        ExecutionException e = assertThrows(ExecutionException.class, () ->
            client.newRequest(newURI(transport))
                .body(new InputStreamRequestContent(new InputStream()
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
        assertThat(e.getCause(), instanceOf(ArrayIndexOutOfBoundsException.class));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDownloadWithCloseBeforeContent(Transport transport) throws Exception
    {
        byte[] data = new byte[128 * 1024];
        byte value = 3;
        Arrays.fill(data, value);
        CountDownLatch latch = new CountDownLatch(1);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                Content.Sink.write(response, false, null);

                try
                {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testDownloadWithCloseMiddleOfContent(Transport transport) throws Exception
    {
        byte[] data1 = new byte[1024];
        byte[] data2 = new byte[1024];
        CountDownLatch latch = new CountDownLatch(1);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                Content.Sink.write(response, false, ByteBuffer.wrap(data1));

                try
                {
                    assertTrue(latch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException();
                }

                response.write(true, ByteBuffer.wrap(data2), callback);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testDownloadWithCloseEndOfContent(Transport transport) throws Exception
    {
        byte[] data = new byte[1024];
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(data), callback);
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
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
    @MethodSource("transports")
    public void testUploadWithDeferredContentProviderFromInputStream(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        CountDownLatch requestSentLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        try (AsyncRequestContent content = new AsyncRequestContent())
        {
            client.newRequest(newURI(transport))
                .body(content)
                .onRequestCommit((request) -> requestSentLatch.countDown())
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == 200)
                        responseLatch.countDown();
                });

            // Make sure we provide the content *after* the request has been "sent".
            assertTrue(requestSentLatch.await(5, TimeUnit.SECONDS));

            try (ByteArrayInputStream input = new ByteArrayInputStream(new byte[1024]))
            {
                byte[] buffer = new byte[200];
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    content.write(ByteBuffer.wrap(buffer, 0, read), Callback.NOOP);
                }
            }
        }
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUploadWithDeferredContentAvailableCallbacksNotifiedOnce(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger succeeds = new AtomicInteger();
        try (AsyncRequestContent content = new AsyncRequestContent())
        {
            // Make the content immediately available.
            content.write(ByteBuffer.allocate(1024), new Callback()
            {
                @Override
                public void succeeded()
                {
                    succeeds.incrementAndGet();
                }
            });

            client.newRequest(newURI(transport))
                .body(content)
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
    @MethodSource("transports")
    public void testUploadWithDeferredContentProviderRacingWithSend(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        byte[] data = new byte[512];
        AsyncRequestContent content = new AsyncRequestContent()
        {
            @Override
            public void demand(Runnable demandCallback)
            {
                super.demand(demandCallback);
                // Simulate a concurrent call
                write(ByteBuffer.wrap(data), Callback.NOOP);
                close();
            }
        };

        client.newRequest(newURI(transport))
            .body(content)
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
    @MethodSource("transports")
    public void testUploadWithOutputStream(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        byte[] data = new byte[512];
        CountDownLatch latch = new CountDownLatch(1);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        client.newRequest(newURI(transport))
            .body(content)
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
    @MethodSource("transports")
    public void testBigUploadWithOutputStreamFromInputStream(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });

        byte[] data = new byte[16 * 1024 * 1024];
        new Random().nextBytes(data);
        CountDownLatch latch = new CountDownLatch(1);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        client.newRequest(newURI(transport))
            .body(content)
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

        try (OutputStream output = content.getOutputStream())
        {
            IO.copy(new ByteArrayInputStream(data), output);
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUploadWithOutputStreamFailureToConnect(Transport transport) throws Exception
    {
        // Failure to connect is based on InetSocketAddress failure, which Unix-Domain does not use.
        Assumptions.assumeTrue(transport != Transport.UNIX_DOMAIN);

        long connectTimeout = 1000;
        start(transport, new EmptyServerHandler());
        client.setConnectTimeout(connectTimeout);

        byte[] data = new byte[512];
        CountDownLatch latch = new CountDownLatch(1);
        OutputStreamRequestContent content = new OutputStreamRequestContent();
        String uri = "http://0.0.0.1";
        client.newRequest(uri)
            .body(content)
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
    @MethodSource("transports")
    public void testUploadWithDeferredContentProviderFailsMultipleOffers(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());

        CountDownLatch failLatch = new CountDownLatch(2);
        Callback callback = new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                failLatch.countDown();
            }
        };

        CountDownLatch completeLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent();
        client.newRequest(newURI(transport))
            .body(content)
            .onRequestBegin(request ->
            {
                content.write(ByteBuffer.wrap(new byte[256]), callback);
                content.write(ByteBuffer.wrap(new byte[256]), callback);
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
        CountDownLatch latch = new CountDownLatch(1);
        content.write(ByteBuffer.wrap(new byte[128]), new Callback()
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
    @MethodSource("transports")
    public void testUploadWithConnectFailureClosesStream(Transport transport) throws Exception
    {
        // Failure to connect is based on InetSocket address failure, which Unix-Domain does not use.
        Assumptions.assumeTrue(transport != Transport.UNIX_DOMAIN);

        long connectTimeout = 1000;
        start(transport, new EmptyServerHandler());
        client.setConnectTimeout(connectTimeout);

        CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8))
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };
        InputStreamRequestContent content = new InputStreamRequestContent(stream);

        CountDownLatch completeLatch = new CountDownLatch(1);
        // TODO: fix scheme
        String uri = "http://0.0.0.1";
        client.newRequest(uri)
            .body(content)
            .send(result ->
            {
                Assertions.assertTrue(result.isFailed());
                completeLatch.countDown();
            });

        assertTrue(completeLatch.await(2 * connectTimeout, TimeUnit.MILLISECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testUploadWithConcurrentServerCloseClosesStream(Transport transport) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                serverLatch.countDown();
                // Do not complete the callback.
            }
        });

        AtomicBoolean commit = new AtomicBoolean();
        CountDownLatch closeLatch = new CountDownLatch(1);
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
        InputStreamRequestContent content = new InputStreamRequestContent(stream, 1);

        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .body(content)
            .onRequestCommit(request -> commit.set(true))
            .send(result ->
            {
                Assertions.assertTrue(result.isFailed());
                completeLatch.countDown();
            });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testInputStreamResponseListenerBufferedRead(Transport transport) throws Exception
    {
        AtomicReference<HandlerContext> contextRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                contextRef.set(new HandlerContext(request, response, callback));
                latch.countDown();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        HandlerContext context = contextRef.get();
        assertNotNull(context);

        Random random = new Random();

        byte[] chunk = new byte[64];
        random.nextBytes(chunk);
        context.response().write(false, ByteBuffer.wrap(chunk), Callback.NOOP);

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

        context.response().write(true, BufferUtil.EMPTY_BUFFER, context.callback());

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testInputStreamResponseListenerWithRedirect(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                if (request.getPathInContext().startsWith("/303"))
                    org.eclipse.jetty.server.Response.sendRedirect(request, response, callback, "/200");
                callback.succeeded();
            }
        });

        InputStreamResponseListener listener = new InputStreamResponseListener();
        client.newRequest(newURI(transport))
            .path("/303")
            .followRedirects(true)
            .send(listener);

        Response response = listener.get(5, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());

        Result result = listener.await(5, TimeUnit.SECONDS);
        assertTrue(result.isSucceeded());
    }

    private record HandlerContext(Request request, org.eclipse.jetty.server.Response response, Callback callback)
    {
    }
}

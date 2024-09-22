//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientContinueTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithOneContentRespond100Continue(Transport transport) throws Exception
    {
        testExpect100ContinueRespond100Continue(transport, "data1".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithMultipleContentsRespond100Continue(Transport transport) throws Exception
    {
        testExpect100ContinueRespond100Continue(transport, "data1".getBytes(StandardCharsets.UTF_8), "data2".getBytes(StandardCharsets.UTF_8), "data3".getBytes(StandardCharsets.UTF_8));
    }

    private void testExpect100ContinueRespond100Continue(Transport transport, byte[]... contents) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send 100-Continue and copy the content back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(contents))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        int index = 0;
        byte[] responseContent = response.getContent();
        for (byte[] content : contents)
        {
            for (byte b : content)
            {
                assertEquals(b, responseContent[index++]);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithMultipleContentsRespond100ContinueBlocking(Transport transport) throws Exception
    {
        byte[][] contents = new byte[][]{
            "data1".getBytes(StandardCharsets.UTF_8), "data2".getBytes(StandardCharsets.UTF_8), "data3".getBytes(StandardCharsets.UTF_8)
        };
        AtomicReference<Thread> readerThreadRef = new AtomicReference<>();
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                readerThreadRef.set(Thread.currentThread());
                // Send 100-Continue and copy the content back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        ContentResponse response;
        try (AsyncRequestContent content = new AsyncRequestContent())
        {
            new Thread(() ->
            {
                for (byte[] b : contents)
                {
                    try
                    {
                        // ensure that the reader will block/pause even after sending 100.
                        await().atMost(5, TimeUnit.SECONDS).until(() ->
                        {
                            Thread thread = readerThreadRef.get();
                            if (thread == null)
                                return false;
                            return thread.getState() == Thread.State.WAITING;
                        });
                        Callback.Completable callback = new Callback.Completable();
                        content.write(b == contents[contents.length - 1], ByteBuffer.wrap(b), callback);
                        callback.get();
                    }
                    catch (Throwable t)
                    {
                        t.printStackTrace();
                    }
                }
            }).start();
            response = client.newRequest(newURI(transport))
                .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
                .body(content)
                .timeout(5, TimeUnit.SECONDS)
                .send();
        }

        assertNotNull(response);
        assertEquals(200, response.getStatus());

        int index = 0;
        byte[] responseContent = response.getContent();
        for (byte[] content : contents)
        {
            for (byte b : content)
            {
                assertEquals(b, responseContent[index++]);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithChunkedContentRespond100Continue(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send 100-Continue and copy the content back
                ServletInputStream input = request.getInputStream();
                // Make sure we chunk the response too
                response.flushBuffer();
                IO.copy(input, response.getOutputStream());
            }
        });

        byte[] content1 = new byte[10240];
        byte[] content2 = new byte[16384];
        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content1, content2)
            {
                @Override
                public long getLength()
                {
                    return -1;
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertNotNull(response);
        assertEquals(200, response.getStatus());
        if (EnumSet.of(Transport.HTTP, Transport.HTTPS).contains(transport))
            assertTrue(response.getHeaders().contains(HttpHeader.TRANSFER_ENCODING, "chunked"));

        int index = 0;
        byte[] responseContent = response.getContent();
        for (byte b : content1)
        {
            assertEquals(b, responseContent[index++]);
        }
        for (byte b : content2)
        {
            assertEquals(b, responseContent[index++]);
        }
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentRespond417ExpectationFailed(Transport transport) throws Exception
    {
        testExpect100ContinueWithContentRespondError(transport, HttpStatus.EXPECTATION_FAILED_417);
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentRespond413RequestEntityTooLarge(Transport transport) throws Exception
    {
        testExpect100ContinueWithContentRespondError(transport, HttpStatus.PAYLOAD_TOO_LARGE_413);
    }

    private void testExpect100ContinueWithContentRespondError(Transport transport, int error) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.sendError(error);
            }
        });

        byte[] content1 = new byte[10240];
        byte[] content2 = new byte[16384];
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content1, content2))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isFailed());
                    assertNotNull(result.getRequestFailure());
                    assertEquals(error, result.getResponse().getStatus());
                    Throwable responseFailure = result.getResponseFailure();
                    // For HTTP/2 the response may fail because the
                    // server may not fully read the request content,
                    // and sends a reset that may drop the response
                    // content and cause the response failure.
                    if (responseFailure == null)
                    {
                        byte[] content = getContent();
                        assertNotNull(content);
                        assertTrue(content.length > 0);
                    }
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentWithRedirect(Transport transport) throws Exception
    {
        String data = "success";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().endsWith("/done"))
                {
                    response.getOutputStream().print(data);
                }
                else
                {
                    // Send 100-Continue and consume the content
                    IO.copy(request.getInputStream(), new ByteArrayOutputStream());
                    // Send a redirect
                    response.sendRedirect("/done");
                }
            }
        });

        byte[] content = new byte[10240];
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .path("/continue")
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertFalse(result.isFailed());
                    assertEquals(200, result.getResponse().getStatus());
                    assertEquals(data, getContentAsString());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testRedirectWithExpect100ContinueWithContent(Transport transport) throws Exception
    {
        // A request with Expect: 100-Continue cannot receive non-final responses like 3xx

        String data = "success";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (request.getRequestURI().endsWith("/done"))
                {
                    // Send 100-Continue and consume the content
                    IO.copy(request.getInputStream(), new ByteArrayOutputStream());
                    response.getOutputStream().print(data);
                }
                else
                {
                    // Send a redirect
                    response.sendRedirect("/done");
                }
            }
        });

        byte[] content = new byte[10240];
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .path("/redirect")
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isFailed());
                    assertNotNull(result.getRequestFailure());
                    assertNull(result.getResponseFailure());
                    assertEquals(302, result.getResponse().getStatus());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentWithResponseFailureBefore100Continue(Transport transport) throws Exception
    {
        AtomicReference<Request> clientRequestRef = new AtomicReference<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        CountDownLatch serverLatch = new CountDownLatch(1);

        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                clientRequestRef.get().abort(new Exception("abort!"));
                try
                {
                    if (!clientLatch.await(5, TimeUnit.SECONDS))
                        throw new ServletException("Server timed out on client latch");
                    serverLatch.countDown();
                }
                catch (InterruptedException e)
                {
                    throw new ServletException(e);
                }
            }
        });

        byte[] content = new byte[1024];
        Request clientRequest = client.newRequest(newURI(transport));
        clientRequestRef.set(clientRequest);
        clientRequest
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isFailed());
                    assertNotNull(result.getRequestFailure());
                    assertNotNull(result.getResponseFailure());
                    clientLatch.countDown();
                }
            });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentWithResponseFailureAfter100Continue(Transport transport) throws Exception
    {
        AtomicReference<Request> clientRequestRef = new AtomicReference<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Send 100-Continue and consume the content
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
                clientRequestRef.get().abort(new Exception("abort!"));
                try
                {
                    if (!clientLatch.await(5, TimeUnit.SECONDS))
                        throw new ServletException("Server timed out on client latch");
                    serverLatch.countDown();
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        byte[] content = new byte[1024];
        Request clientRequest = client.newRequest(newURI(transport));
        clientRequestRef.set(clientRequest);
        clientRequest
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isFailed());
                    assertNull(result.getRequestFailure());
                    assertNotNull(result.getResponseFailure());
                    clientLatch.countDown();
                }
            });

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentWithResponseFailureDuring100Continue(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send 100-Continue and consume the content
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        client.getProtocolHandlers().clear();
        client.getProtocolHandlers().put(new ContinueProtocolHandler()
        {
            @Override
            public Response.Listener getResponseListener()
            {
                Response.Listener listener = super.getResponseListener();
                return new Response.Listener()
                {
                    @Override
                    public void onBegin(Response response)
                    {
                        response.abort(new Exception());
                    }

                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        listener.onFailure(response, failure);
                    }
                };
            }
        });

        byte[] content = new byte[1024];
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isFailed());
                    assertNotNull(result.getRequestFailure());
                    assertNotNull(result.getResponseFailure());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        byte[] chunk1 = new byte[]{0, 1, 2, 3};
        byte[] chunk2 = new byte[]{4, 5, 6, 7};
        byte[] data = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, data, 0, chunk1.length);
        System.arraycopy(chunk2, 0, data, chunk1.length, chunk2.length);

        CountDownLatch serverLatch = new CountDownLatch(1);
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                handlerThread.set(Thread.currentThread());
                // Send 100-Continue and echo the content

                ServletOutputStream outputStream = response.getOutputStream();
                DataInputStream inputStream = new DataInputStream(request.getInputStream());
                // Block until the 1st chunk is fully received.
                byte[] buf1 = new byte[chunk1.length];
                inputStream.readFully(buf1);
                outputStream.write(buf1);

                serverLatch.countDown();
                IO.copy(inputStream, outputStream);
            }
        });

        CountDownLatch requestLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent();
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertArrayEquals(data, getContent());
                    requestLatch.countDown();
                }
            });

        // Wait for the handler thread to be blocked in the 1st IO.
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread thread = handlerThread.get();
            return thread != null && thread.getState() == Thread.State.WAITING;
        });

        content.write(ByteBuffer.wrap(chunk1), Callback.NOOP);

        // Wait for the handler thread to be blocked in the 2nd IO.
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread thread = handlerThread.get();
            return thread != null && thread.getState() == Thread.State.WAITING;
        });

        content.write(ByteBuffer.wrap(chunk2), Callback.NOOP);
        content.close();

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithInitialAndDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                handlerThread.set(Thread.currentThread());
                // Send 100-Continue and echo the content
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] chunk1 = new byte[]{0, 1, 2, 3};
        byte[] chunk2 = new byte[]{4, 5, 6, 7};
        byte[] data = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, data, 0, chunk1.length);
        System.arraycopy(chunk2, 0, data, chunk1.length, chunk2.length);

        CountDownLatch latch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(chunk1));
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        // Wait for the handler thread to be blocked in IO.
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread thread = handlerThread.get();
            return thread != null && thread.getState() == Thread.State.WAITING;
        });

        content.write(ByteBuffer.wrap(chunk2), Callback.NOOP);
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithConcurrentDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send 100-Continue and echo the content
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        AsyncRequestContent content = new AsyncRequestContent();

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .onRequestHeaders(request ->
            {
                content.write(ByteBuffer.wrap(data), Callback.NOOP);
                content.close();
            })
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithInitialAndConcurrentDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send 100-Continue and echo the content
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] chunk1 = new byte[]{0, 1, 2, 3};
        byte[] chunk2 = new byte[]{4, 5, 6};
        byte[] data = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, data, 0, chunk1.length);
        System.arraycopy(chunk2, 0, data, chunk1.length, chunk2.length);

        AsyncRequestContent content = new AsyncRequestContent(ByteBuffer.wrap(chunk1));

        client.getProtocolHandlers().put(new ContinueProtocolHandler()
        {
            @Override
            public Response.Listener getResponseListener()
            {
                return new ContinueListener()
                {
                    @Override
                    public void onHeaders(Response response)
                    {
                        super.onHeaders(response);
                        content.write(ByteBuffer.wrap(chunk2), Callback.NOOP);
                        content.close();
                    }
                };
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(content)
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertArrayEquals(data, getContent());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void test100ContinueThenTimeoutThenSendError(Transport transport) throws Exception
    {
        long idleTimeout = 1000;

        CountDownLatch serverLatch = new CountDownLatch(1);
        startServer(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                // Send the 100 Continue.
                ServletInputStream input = request.getInputStream();
                try
                {
                    // Echo the content.
                    IO.copy(input, response.getOutputStream());
                }
                catch (IOException x)
                {
                    // The copy failed b/c of idle timeout, time to try
                    // to send an error which should have no effect.
                    response.sendError(HttpStatus.IM_A_TEAPOT_418);
                    serverLatch.countDown();
                }
            }
        });
        startClient(transport, httpClient -> httpClient.setIdleTimeout(idleTimeout));

        AsyncRequestContent requestContent = new AsyncRequestContent();
        requestContent.write(ByteBuffer.wrap(new byte[512]), Callback.NOOP);
        CountDownLatch clientLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
            .body(requestContent)
            .send(result ->
            {
                if (result.isFailed() && result.getResponse().getStatus() == HttpStatus.CONTINUE_100)
                    clientLatch.countDown();
            });

        // Wait more than the idle timeout to break the connection.
        Thread.sleep(2 * idleTimeout);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testExpect100ContinueWithContentLengthZeroExpectIsRemoved(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                assertEquals(0, request.getContentLengthLong());
                // The Expect header must have been removed by the client.
                assertNull(request.getHeader(HttpHeader.EXPECT.asString()));
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString()))
            .body(new StringRequestContent(""))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testExpect100ContinueWithContentLengthZero() throws Exception
    {
        startServer(Transport.HTTP, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                assertEquals(0, request.getContentLengthLong());
                assertNotNull(request.getHeader(HttpHeader.EXPECT.asString()));

                // Trigger the 100-Continue logic.
                // The 100 continue will not be sent, since there is no request content.
                ServletInputStream input = request.getInputStream();
                assertEquals(-1, input.read());
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", ((NetworkConnector)connector).getLocalPort())))
        {
            String request = """
                GET / HTTP/1.1
                Host: localhost
                Expect: 100-Continue
                Content-Length: 0
                
                """;
            client.write(StandardCharsets.UTF_8.encode(request));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testExpect100ContinueWithTwoResponsesInOneRead() throws Exception
    {
        // There is a chance that the server replies with the 100 Continue response
        // and immediately after with the "normal" response, say a 200 OK.
        // These may be read by the client in a single read, and must be handled correctly.

        startClient(Transport.HTTP);

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            CountDownLatch latch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
                .body(new BytesRequestContent(new byte[]{0}))
                .send(result ->
                {
                    assertTrue(result.isSucceeded(), result.toString());
                    assertEquals(200, result.getResponse().getStatus());
                    latch.countDown();
                });

            try (Socket socket = server.accept())
            {
                // Read only the request headers.
                readRequestHeaders(socket.getInputStream());

                OutputStream output = socket.getOutputStream();
                String responses = """
                    HTTP/1.1 100 Continue\r
                    \r
                    HTTP/1.1 200 OK\r
                    Transfer-Encoding: chunked\r
                    \r
                    10\r
                    0123456789ABCDEF\r
                    """;
                output.write(responses.getBytes(StandardCharsets.UTF_8));
                output.flush();

                Thread.sleep(1000);

                String content = """
                    10\r
                    0123456789ABCDEF\r
                    0\r
                    \r
                    """;
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();

                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void testNoExpectRespond100Continue() throws Exception
    {
        startClient(Transport.HTTP);
        client.setMaxConnectionsPerDestination(1);

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            byte[] bytes = new byte[1024];
            new Random().nextBytes(bytes);
            Request clientRequest = client.newRequest("localhost", server.getLocalPort())
                .body(new BytesRequestContent(bytes))
                .timeout(5, TimeUnit.SECONDS);
            CompletableFuture<ContentResponse> completable = new CompletableResponseListener(clientRequest).send();

            try (Socket socket = server.accept())
            {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                HttpTester.Request serverRequest = HttpTester.parseRequest(input);
                assertNotNull(serverRequest);
                byte[] content = serverRequest.getContentBytes();

                String serverResponse = """
                    HTTP/1.1 100 Continue\r
                    \r
                    HTTP/1.1 200 OK\r
                    Content-Length: $L\r
                    \r
                    """.replace("$L", String.valueOf(content.length));
                output.write(serverResponse.getBytes(StandardCharsets.UTF_8));
                output.write(content);
                output.flush();
            }

            ContentResponse response = completable.get(5, TimeUnit.SECONDS);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertArrayEquals(bytes, response.getContent());
        }
    }

    @Test
    public void testNoExpect100ContinueThen100ContinueThenRedirectThen100ContinueThenResponse() throws Exception
    {
        startClient(Transport.HTTP);
        client.setMaxConnectionsPerDestination(1);

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            // No Expect header, no content.
            CountDownLatch latch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });

            try (Socket socket = server.accept())
            {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                HttpTester.parseRequest(input);
                String response1 = """
                    HTTP/1.1 100 Continue\r
                    \r
                    HTTP/1.1 303 See Other\r
                    Location: /redirect\r
                    Content-Length: 0\r
                    \r
                    """;
                output.write(response1.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.parseRequest(input);
                String response2 = """
                    HTTP/1.1 100 Continue\r
                    \r
                    HTTP/1.1 200 OK\r
                    Content-Length: 0\r
                    Connection: close\r
                    \r
                    """;
                output.write(response2.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    private void readRequestHeaders(InputStream input) throws IOException
    {
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            if (read < 0)
                break;
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
    }
}

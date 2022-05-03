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

package org.eclipse.jetty.ee9.http.client;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.ee9.http.client.Transport.FCGI;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HttpClientContinueTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        // Skip FCGI for now.
        assumeTrue(transport != FCGI);
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithOneContentRespond100Continue(Transport transport) throws Exception
    {
        testExpect100ContinueRespond100Continue(transport, "data1".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithMultipleContentsRespond100Continue(Transport transport) throws Exception
    {
        testExpect100ContinueRespond100Continue(transport, "data1".getBytes(StandardCharsets.UTF_8), "data2".getBytes(StandardCharsets.UTF_8), "data3".getBytes(StandardCharsets.UTF_8));
    }

    private void testExpect100ContinueRespond100Continue(Transport transport, byte[]... contents) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and copy the content back
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        ContentResponse response = scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithChunkedContentRespond100Continue(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and copy the content back
                ServletInputStream input = request.getInputStream();
                // Make sure we chunk the response too
                response.flushBuffer();
                IO.copy(input, response.getOutputStream());
            }
        });

        byte[] content1 = new byte[10240];
        byte[] content2 = new byte[16384];
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithContentRespond417ExpectationFailed(Transport transport) throws Exception
    {
        testExpect100ContinueWithContentRespondError(transport, 417);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithContentRespond413RequestEntityTooLarge(Transport transport) throws Exception
    {
        testExpect100ContinueWithContentRespondError(transport, 413);
    }

    private void testExpect100ContinueWithContentRespondError(Transport transport, int error) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.sendError(error);
            }
        });

        byte[] content1 = new byte[10240];
        byte[] content2 = new byte[16384];
        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(new BytesRequestContent(content1, content2))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isFailed());
                    assertNotNull(result.getRequestFailure());
                    assertNull(result.getResponseFailure());
                    byte[] content = getContent();
                    assertNotNull(content);
                    assertTrue(content.length > 0);
                    assertEquals(error, result.getResponse().getStatus());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithContentWithRedirect(Transport transport) throws Exception
    {
        init(transport);
        String data = "success";
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
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
        scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testRedirectWithExpect100ContinueWithContent(Transport transport) throws Exception
    {
        init(transport);
        // A request with Expect: 100-Continue cannot receive non-final responses like 3xx

        String data = "success";
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
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
        scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithContentWithResponseFailureBefore100Continue(Transport transport) throws Exception
    {
        init(transport);
        AtomicReference<org.eclipse.jetty.client.api.Request> clientRequestRef = new AtomicReference<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        CountDownLatch serverLatch = new CountDownLatch(1);

        scenario.startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                baseRequest.setHandled(true);
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
        scenario.startClient();

        byte[] content = new byte[1024];
        org.eclipse.jetty.client.api.Request clientRequest = scenario.client.newRequest(scenario.newURI());
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithContentWithResponseFailureAfter100Continue(Transport transport) throws Exception
    {
        init(transport);
        AtomicReference<org.eclipse.jetty.client.api.Request> clientRequestRef = new AtomicReference<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        CountDownLatch serverLatch = new CountDownLatch(1);
        scenario.startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
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
        scenario.startClient();

        byte[] content = new byte[1024];
        org.eclipse.jetty.client.api.Request clientRequest = scenario.client.newRequest(scenario.newURI());
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithContentWithResponseFailureDuring100Continue(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and consume the content
                IO.copy(request.getInputStream(), new ByteArrayOutputStream());
            }
        });

        scenario.client.getProtocolHandlers().clear();
        scenario.client.getProtocolHandlers().put(new ContinueProtocolHandler()
        {
            @Override
            public Response.Listener getResponseListener()
            {
                Response.Listener listener = super.getResponseListener();
                return new Response.Listener.Adapter()
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
        scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        byte[] chunk1 = new byte[]{0, 1, 2, 3};
        byte[] chunk2 = new byte[]{4, 5, 6, 7};
        byte[] data = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, data, 0, chunk1.length);
        System.arraycopy(chunk2, 0, data, chunk1.length, chunk2.length);

        CountDownLatch serverLatch = new CountDownLatch(1);
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
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
        scenario.client.newRequest(scenario.newURI())
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

        content.offer(ByteBuffer.wrap(chunk1));

        // Wait for the handler thread to be blocked in the 2nd IO.
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread thread = handlerThread.get();
            return thread != null && thread.getState() == Thread.State.WAITING;
        });

        content.offer(ByteBuffer.wrap(chunk2));
        content.close();

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithInitialAndDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
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
        scenario.client.newRequest(scenario.newURI())
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

        content.offer(ByteBuffer.wrap(chunk2));
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithConcurrentDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                // Send 100-Continue and echo the content
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] data = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
        AsyncRequestContent content = new AsyncRequestContent();

        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .onRequestHeaders(request ->
            {
                content.offer(ByteBuffer.wrap(data));
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithInitialAndConcurrentDeferredContentRespond100Continue(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
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

        scenario.client.getProtocolHandlers().put(new ContinueProtocolHandler()
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
                        content.offer(ByteBuffer.wrap(chunk2));
                        content.close();
                    }
                };
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
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
    @ArgumentsSource(TransportProvider.class)
    public void testExpect100ContinueWithTwoResponsesInOneRead(Transport transport) throws Exception
    {
        init(transport);
        assumeTrue(scenario.transport.isHttp1Based());

        // There is a chance that the server replies with the 100 Continue response
        // and immediately after with the "normal" response, say a 200 OK.
        // These may be read by the client in a single read, and must be handled correctly.

        scenario.startClient();

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            CountDownLatch latch = new CountDownLatch(1);
            scenario.client.newRequest("localhost", server.getLocalPort())
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
                // Read the request headers.
                readRequestHeaders(socket.getInputStream());

                OutputStream output = socket.getOutputStream();
                String responses =
                    "HTTP/1.1 100 Continue\r\n" +
                        "\r\n" +
                        "HTTP/1.1 200 OK\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "10\r\n" +
                        "0123456789ABCDEF\r\n";
                output.write(responses.getBytes(StandardCharsets.UTF_8));
                output.flush();

                Thread.sleep(1000);

                String content =
                    "10\r\n" +
                        "0123456789ABCDEF\r\n" +
                        "0\r\n" +
                        "\r\n";
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();

                assertTrue(latch.await(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testNoExpectRespond100Continue(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);
                // Force a 100 Continue response.
                jettyRequest.getHttpChannel().sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                // Echo the content.
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .body(new BytesRequestContent(bytes))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(bytes, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testNoExpect100ContinueThenRedirectThen100ContinueThenResponse(Transport transport) throws Exception
    {
        init(transport);
        assumeTrue(scenario.transport.isHttp1Based());

        scenario.startClient();
        scenario.client.setMaxConnectionsPerDestination(1);

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            // No Expect header, no content.
            CountDownLatch latch = new CountDownLatch(1);
            scenario.client.newRequest("localhost", server.getLocalPort())
                .send(result ->
                {
                    if (result.isSucceeded() && result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });

            try (Socket socket = server.accept())
            {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                readRequestHeaders(input);
                String response1 =
                    "HTTP/1.1 100 Continue\r\n" +
                        "\r\n" +
                        "HTTP/1.1 303 See Other\r\n" +
                        "Location: /redirect\r\n" +
                        "Content-Length: 0\r\n" +
                        "\r\n";
                output.write(response1.getBytes(StandardCharsets.UTF_8));
                output.flush();

                readRequestHeaders(input);
                String response2 =
                    "HTTP/1.1 100 Continue\r\n" +
                        "\r\n" +
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
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

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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpInputInterceptorTest
{
    private Server server;
    private HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
    private ServerConnector connector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1, httpConnectionFactory);
        server.addConnector(connector);

        server.setHandler(handler);

        client = new HttpClient();
        server.addBean(client);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testBlockingReadInterceptorThrows() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                jettyRequest.setHandled(true);

                // Throw immediately from the interceptor.
                jettyRequest.getHttpInput().addInterceptor(content ->
                {
                    throw new RuntimeException();
                });

                assertThrows(IOException.class, () -> IO.readBytes(request.getInputStream()));
                serverLatch.countDown();
                response.setStatus(HttpStatus.NO_CONTENT_204);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .content(new BytesContentProvider(new byte[1]))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
    }

    @Test
    public void testBlockingReadInterceptorConsumesHalfThenThrows() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                jettyRequest.setHandled(true);

                // Consume some and then throw.
                AtomicInteger readCount = new AtomicInteger();
                jettyRequest.getHttpInput().addInterceptor(content ->
                {
                    int reads = readCount.incrementAndGet();
                    if (reads == 1)
                    {
                        ByteBuffer buffer = content.getByteBuffer();
                        int half = buffer.remaining() / 2;
                        int limit = buffer.limit();
                        buffer.limit(buffer.position() + half);
                        ByteBuffer chunk = buffer.slice();
                        buffer.position(buffer.limit());
                        buffer.limit(limit);
                        return new HttpInput.Content(chunk);
                    }
                    throw new RuntimeException();
                });

                assertThrows(IOException.class, () -> IO.readBytes(request.getInputStream()));
                serverLatch.countDown();
                response.setStatus(HttpStatus.NO_CONTENT_204);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .content(new BytesContentProvider(new byte[1024]))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testAsyncResponseWithoutReadingRequestContentWithInterceptorThatThrows(boolean commitResponse) throws Exception
    {
        AtomicLong onFillableCount = new AtomicLong();
        httpConnectionFactory = new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                HttpConnection connection = new HttpConnection(getHttpConfiguration(), connector, endPoint, getHttpCompliance(), isRecordHttpComplianceViolations())
                {
                    @Override
                    public void onFillable()
                    {
                        onFillableCount.incrementAndGet();
                        super.onFillable();
                    }
                };
                return configure(connection, connector, endPoint);
            }
        };

        long delay = 500;
        CountDownLatch contentLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);

                AtomicInteger readCount = new AtomicInteger();
                jettyRequest.getHttpInput().addInterceptor(content ->
                {
                    if (readCount.incrementAndGet() == 1)
                    {
                        // Tell the client to write more content.
                        contentLatch.countDown();
                        // Wait to let the content arrive to the server.
                        sleep(delay);
                    }
                    throw new RuntimeException();
                });

                AsyncContext asyncContext = request.startAsync();
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (commitResponse)
                            response.getOutputStream().close();
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable error)
                    {
                        error.printStackTrace();
                    }
                });
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            // The request must have a content chunk so that it gets dispatched.
            String request = "" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "1\r\n" +
                "A\r\n";
            client.write(StandardCharsets.UTF_8.encode(request));

            // Write the remaining content.
            // This triggers to fill and parse again after consumeAll(),
            // and we want to verify that the code does not spin.
            assertTrue(contentLatch.await(5, TimeUnit.SECONDS));
            String content = "" +
                "1\r\n" +
                "X\r\n" +
                "0\r\n" +
                "\r\n";
            client.write(StandardCharsets.UTF_8.encode(content));

            // Wait and verify that we did not spin.
            sleep(4 * delay);
            assertThat(onFillableCount.get(), Matchers.lessThan(10L));

            // Connection must be closed by the server.
            Socket socket = client.socket();
            socket.setSoTimeout(1000);
            InputStream input = socket.getInputStream();

            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            try
            {
                while (true)
                {
                    if (input.read() < 0)
                        break;
                }
            }
            catch (IOException ignored)
            {
                // Java 8 may throw IOException: Connection reset by peer
                // but that's ok (the server closed the connection).
            }
        }
    }

    @Test
    public void testAvailableReadInterceptorThrows() throws Exception
    {
        CountDownLatch interceptorLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);

                // Throw immediately from the interceptor.
                jettyRequest.getHttpInput().addInterceptor(content ->
                {
                    interceptorLatch.countDown();
                    throw new RuntimeException();
                });

                int available = request.getInputStream().available();
                assertEquals(0, available);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(interceptorLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testIsReadyReadInterceptorThrows() throws Exception
    {
        byte[] bytes = new byte[]{13};
        CountDownLatch interceptorLatch = new CountDownLatch(1);
        CountDownLatch readFailureLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);

                AtomicBoolean onDataAvailable = new AtomicBoolean();
                jettyRequest.getHttpInput().addInterceptor(content ->
                {
                    if (onDataAvailable.get())
                    {
                        interceptorLatch.countDown();
                        throw new RuntimeException();
                    }
                    else
                    {
                        return content;
                    }
                });

                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                        onDataAvailable.set(true);
                        // Now the interceptor should throw, but isReady() should not.
                        if (input.isReady())
                        {
                            assertThrows(IOException.class, () -> assertEquals(bytes[0], input.read()));
                            readFailureLatch.countDown();
                            response.setStatus(HttpStatus.NO_CONTENT_204);
                            asyncContext.complete();
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable error)
                    {
                        error.printStackTrace();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .content(new BytesContentProvider(bytes))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(interceptorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(readFailureLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
    }

    @Test
    public void testSetReadListenerReadInterceptorThrows() throws Exception
    {
        RuntimeException failure = new RuntimeException();
        CountDownLatch interceptorLatch = new CountDownLatch(1);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                jettyRequest.setHandled(true);

                // Throw immediately from the interceptor.
                jettyRequest.getHttpInput().addInterceptor(content ->
                {
                    interceptorLatch.countDown();
                    failure.addSuppressed(new Throwable());
                    throw failure;
                });

                AsyncContext asyncContext = request.startAsync();
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable()
                    {
                    }

                    @Override
                    public void onAllDataRead()
                    {
                    }

                    @Override
                    public void onError(Throwable error)
                    {
                        assertSame(failure, error.getCause());
                        response.setStatus(HttpStatus.NO_CONTENT_204);
                        asyncContext.complete();
                    }
                });
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .content(new BytesContentProvider(new byte[1]))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(interceptorLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
    }

    private static void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }
}

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

package org.eclipse.jetty.ee9.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.nested.AbstractHandler;
import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.nested.Handler;
import org.eclipse.jetty.ee9.nested.HttpInput;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

        server.setHandler(new ContextHandler("/", handler));

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
                jettyRequest.getHttpInput().addInterceptor(_ ->
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
            .body(new BytesRequestContent(new byte[1]))
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
                        try (RetainableByteBuffer buffer = content.acquire())
                        {
                            try (RetainableByteBuffer slice = buffer.sliceAndConsume(buffer.remaining() / 2))
                            {
                                return new HttpInput.Content(slice);
                            }
                        }
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
            .body(new BytesRequestContent(new byte[1024]))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.NO_CONTENT_204, response.getStatus());
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
                jettyRequest.getHttpInput().addInterceptor(_ ->
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
            .body(new BytesRequestContent(new byte[1]))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertTrue(interceptorLatch.await(5, TimeUnit.SECONDS));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testIsReadyReadInterceptorThrows() throws Exception
    {
        AsyncRequestContent asyncRequestContent = new AsyncRequestContent(ByteBuffer.wrap(new byte[1]));
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
        
                        // The input.setReadListener() call called the interceptor so there is content for read().
                        assertThat(input.isReady(), is(true));
                        assertDoesNotThrow(() -> assertEquals(0, input.read()));
        
                        // Make the client send more content so that the interceptor will be called again.
                        asyncRequestContent.write(false, RetainableByteBuffer.wrap(new byte[1]), Callback.NOOP);
                        asyncRequestContent.close();
                        sleep(500); // Wait a little to make sure the content arrived by next isReady() call.
        
                        // The interceptor should throw, but isReady() should not.
                        assertThat(input.isReady(), is(true));
                        assertThrows(IOException.class, () -> assertEquals(0, input.read()));
                        readFailureLatch.countDown();
                        response.setStatus(HttpStatus.NO_CONTENT_204);
                        asyncContext.complete();
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
            .body(asyncRequestContent)
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
                jettyRequest.getHttpInput().addInterceptor(_ ->
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
            .body(new BytesRequestContent(new byte[1]))
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

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

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IdleTimeoutAsyncReadTest
{
    private Server server;
    private ServerConnector connector;

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testIdleTimeoutDuringAsyncRead() throws Exception
    {
        long idleTimeout = 1000;
        CountDownLatch onErrorLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        server = new Server();
        connector = new ServerConnector(server);
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler("/ctx");
        context.addServlet(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                ServletInputStream input = request.getInputStream();
                input.setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        while (input.isReady())
                        {
                            int b = input.read();
                            if (b < 0)
                                break;
                        }
                    }

                    @Override
                    public void onAllDataRead()
                    {
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        System.err.println("ReadListener.onError: " + t);
                        errorRef.set(t);
                        onErrorLatch.countDown();
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        asyncContext.complete();
                        completeLatch.countDown();
                    }
                });
            }
        }, "/path/*");
        server.setHandler(context);
        server.start();

        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            // Send a POST with Content-Length: 100 but only send 5 bytes of body.
            // Then stop sending — the client becomes idle.
            String request = """
                POST /ctx/path HTTP/1.1\r
                Host: localhost\r
                Content-Length: 100\r
                \r
                hello""";
            channel.write(UTF_8.encode(request));

            // The server's idle timeout fires, ReadListener.onError() is called with TimeoutException.
            assertTrue(onErrorLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS), "ReadListener.onError was not called");
            assertThat(errorRef.get(), instanceOf(TimeoutException.class));

            // Wait for asyncContext.complete() to finish.
            assertTrue(completeLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS), "asyncContext.complete() did not finish");

            // HttpStreamOverHTTP1.succeeded() sees isFillInterested()==true (the stale fill
            // interest from demand() was never cancelled by the idle timeout path) and calls
            // abort(), which closes the endpoint. Verify the server aborted the connection.
            await().atMost(5, TimeUnit.SECONDS).until(() -> connector.getConnectedEndPoints().size(), is(0));
        }
    }
}
